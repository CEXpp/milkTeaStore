package com.milktea.order.delegate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.delegate.entity.PickupToken;
import com.milktea.order.delegate.mapper.PickupTokenMapper;
import com.milktea.order.delegate.vo.DelegatePickupVo;
import com.milktea.order.order.dto.OptionSnapshot;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderItem;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.mapper.OrderItemMapper;
import com.milktea.order.order.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * 取餐凭证转赠服务（T65，C4e · W15）。
 *
 * <p><b>本服务正面打破 SRS 9.2「不可跨 openid 读取」</b>——代取人能拿到取餐码。
 * 这不是疏忽，而是用四条更细的约定把边界补回来，缺一不可：</p>
 * <ol>
 *   <li><b>限时</b>：默认 2 小时，到点自然失效（{@code expires_at > NOW()} 写进核销的
 *       WHERE 条件，由数据库裁决）；</li>
 *   <li><b>一次性</b>：{@code used_at IS NULL} 同样是核销的 WHERE 条件——
 *       两个人同时点开只有一个成功；</li>
 *   <li><b>可撤销</b>：原主随时收回，且<b>原主始终是权限终点</b>；</li>
 *   <li><b>不可转赠</b>：代取人侧<b>没有任何签发接口</b>，令牌无法二次扩散。</li>
 * </ol>
 *
 * <p><b>代取人看不到什么</b>（验收项）：金额、支付渠道、交易号、顾客身份、备注、
 * 其他订单中的任何一个字段。实现方式是<b>取数时就不查</b>——见 {@link DelegatePickupVo}。</p>
 *
 * <p><b>为什么不用 JWT 承载</b>：JWT 无法「用一次即失效」（签发后到过期前一直有效），
 * 一次性是本需求的核心约束之一，故必须用可核销的随机串 + 数据库状态。</p>
 */
@Slf4j
@Service
public class PickupDelegateService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 令牌默认有效期 2 小时：够同事顺路取一杯，又不至于隔夜仍然有效。 */
    private static final int DEFAULT_TTL_MINUTES = 120;
    private static final int MIN_TTL_MINUTES = 10;
    private static final int MAX_TTL_MINUTES = 720;

    /** 可取餐的状态：已支付待制作 / 制作中 / 已出餐。 */
    private static final List<String> PICKABLE_STATUSES = List.of(
            OrderStatus.PAID.name(),
            OrderStatus.PREPARING.name(),
            OrderStatus.COMPLETED.name());

    private final PickupTokenMapper pickupTokenMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    /** 密码学随机源：令牌是唯一授权凭据，可预测等于任何人都能代取。 */
    private final SecureRandom random = new SecureRandom();

    /**
     * 显式构造器。
     *
     * <p>时间判定全部交给 MySQL 的 {@code NOW()}（见 {@code PickupTokenMapper.consume}）——
     * 由数据库裁决比在应用层算更可靠，应用与库时钟不一致时也不会误放行。</p>
     *
     * <p><b>此处绝不能再叠加 {@code @RequiredArgsConstructor}</b>：一旦叠加，本类会有
     * 两个构造器且都无 {@code @Autowired} 标注，Spring 7 将无法决定注入哪一个，
     * 转而去找无参构造器并抛 {@code No default constructor found}——
     * 且该错误只在<b>启动装配期</b>暴露，编译期完全无感。</p>
     */
    public PickupDelegateService(PickupTokenMapper pickupTokenMapper,
                                 OrderMapper orderMapper,
                                 OrderItemMapper orderItemMapper) {
        this.pickupTokenMapper = pickupTokenMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
    }

    /**
     * 原主签发委托令牌。
     *
     * <p><b>同一订单只保留一个有效令牌</b>：签发前先作废本单已有的有效令牌——
     * 否则同一订单会存在多个并行有效的授权，原主撤销时得挨个撤销，容易漏。</p>
     *
     * @param orderId       订单 id
     * @param ownerId       当前登录顾客（须为订单本人）
     * @param minutes       有效期（分钟），空则默认 120
     * @param proxyTag      代取人标识（选填，如「李工」）
     * @return 令牌本体（仅此一次返回；之后无处可查，页面请提示用户自行保存）
     * @throws BusinessException 1005 订单非本人；1004 订单不存在 / 状态不可取餐
     */
    @Transactional(rollbackFor = Exception.class)
    public String issue(Long orderId, Long ownerId, Integer minutes, String proxyTag) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "订单不存在");
        }
        if (!Objects.equals(order.getCustomerId(), ownerId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_BELONG);
        }
        if (!PICKABLE_STATUSES.contains(order.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "该订单当前不可代取");
        }

        // 单一有效令牌：签发新令牌前作废本单已有的
        pickupTokenMapper.revokeAllForOrder(orderId);

        int ttl = minutes == null
                ? DEFAULT_TTL_MINUTES
                : Math.min(Math.max(minutes, MIN_TTL_MINUTES), MAX_TTL_MINUTES);
        LocalDateTime now = LocalDateTime.now();

        PickupToken token = new PickupToken();
        token.setOrderId(orderId);
        token.setToken(newToken());
        token.setProxyTag(StringUtils.hasText(proxyTag) ? proxyTag.trim() : null);
        token.setExpiresAt(now.plusMinutes(ttl));
        token.setRevoked(false);
        token.setCreatedAt(now);
        pickupTokenMapper.insert(token);

        // 令牌本体只在此刻返回，系统内不留可再次查询的明文副本
        log.info("[T65] pickup token issued orderId={} owner={} expiresAt={}",
                orderId, ownerId, token.getExpiresAt());
        return token.getToken();
    }

    /**
     * 原主撤销委托（随时，原主始终是权限终点）。
     *
     * @throws BusinessException 1004 订单不存在；1005 订单非本人
     */
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long orderId, Long ownerId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "订单不存在");
        }
        if (!Objects.equals(order.getCustomerId(), ownerId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_BELONG);
        }
        int affected = pickupTokenMapper.revokeOwned(orderId, ownerId);
        log.info("[T65] pickup token revoked orderId={} owner={} affected={}", orderId, ownerId, affected);
    }

    /**
     * 代取人凭令牌打开凭证页（<b>并当场核销</b>）。
     *
     * <p><b>为什么打开即核销</b>：如果「打开凭证」和「标记已用」是两步，
     * 代取人可以把令牌转发给别人再自己用一次——一次性就名存实亡了。
     * 令牌的语义是「把这一杯的取餐权交给某人一次」，看一眼即用掉才符合这个语义。</p>
     *
     * <p>核销失败（已被用过 / 已撤销 / 已过期）统一返回 1004，不区分具体原因——
     * 告诉调用方「是过期还是被撤销」等于泄露状态给持有令牌的人。</p>
     *
     * @param token   令牌本体
     * @param proxyTag 代取人标识（选填，仅供店员识别来者）
     * @return 取餐码 + 商品概要（<b>无金额、无支付信息</b>）
     * @throws BusinessException 1004 令牌无效 / 已用过 / 已撤销 / 已过期；1001 订单状态不可取餐
     */
    @Transactional(rollbackFor = Exception.class)
    public DelegatePickupVo redeem(String token, String proxyTag) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "令牌不能为空");
        }
        String trimmed = token.trim();

        // 条件更新核销：四条约束（一次性 / 可撤销 / 限时）全在 WHERE 里
        int affected = pickupTokenMapper.consume(trimmed, StringUtils.hasText(proxyTag) ? proxyTag.trim() : null);
        if (affected != 1) {
            log.info("[T65] pickup token redeem rejected（已用 / 已撤销 / 已过期）");
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "取餐凭证已失效，请联系下单人重新获取");
        }

        PickupToken record = pickupTokenMapper.selectOne(new LambdaQueryWrapper<PickupToken>()
                .eq(PickupToken::getToken, trimmed)
                .last("LIMIT 1"));
        if (record == null) {
            // 理论上不可达（刚核销过），真发生说明数据被并发改动，宁可报错也不给半个数据
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "取餐凭证异常");
        }

        Order order = orderMapper.selectById(record.getOrderId());
        if (order == null || !PICKABLE_STATUSES.contains(order.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "该订单当前不可代取");
        }

        // 只取商品名 / 杯数 / 规格：金额、支付、顾客信息一律不进这个方法
        List<DelegatePickupVo.Item> items = new ArrayList<>();
        for (OrderItem item : orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId())
                .orderByAsc(OrderItem::getId))) {
            items.add(new DelegatePickupVo.Item(
                    item.getProductName(),
                    item.getQuantity() == null ? 0 : item.getQuantity(),
                    summarizeOptions(item.getOptionsSnapshot())));
        }

        log.info("[T65] pickup token redeemed orderId={} status={}", order.getId(), order.getStatus());
        return new DelegatePickupVo(
                order.getOrderNo(),
                order.getPickupCode(),
                order.getStatus(),
                items,
                record.getExpiresAt() == null ? null : record.getExpiresAt().format(DATETIME_FMT),
                true,
                false);
    }

    /** 32 字节密码学随机 → Base64URL（43 字符，无填充，URL 安全）。 */
    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 规格一行摘要（给代取人看的，不含价差——价差属金额信息）。 */
    private String summarizeOptions(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            OptionSnapshot[] arr = JSON.readValue(json, OptionSnapshot[].class);
            if (arr == null || arr.length == 0) {
                return null;
            }
            List<String> names = new ArrayList<>(arr.length);
            for (OptionSnapshot option : arr) {
                names.add(option.getOptionName());
            }
            return String.join(" · ", names);
        } catch (Exception e) {
            log.warn("[T65] 规格快照解析失败，代取页按无规格返回：{}", e.getMessage());
            return null;
        }
    }
}