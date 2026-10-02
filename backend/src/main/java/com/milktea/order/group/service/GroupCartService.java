package com.milktea.order.group.service;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.result.R;
import com.milktea.order.common.util.MoneyUtils;
import com.milktea.order.group.dto.GroupCreateRequest;
import com.milktea.order.group.dto.GroupItemSubmitRequest;
import com.milktea.order.group.dto.MemberDraft;
import com.milktea.order.group.entity.GroupCart;
import com.milktea.order.group.mapper.GroupCartMapper;
import com.milktea.order.group.vo.GroupCartVo;
import com.milktea.order.order.dto.OrderItemRequest;
import com.milktea.order.order.dto.PricedItem;
import com.milktea.order.order.dto.PricingResult;
import com.milktea.order.order.dto.TaggedItemRequest;
import com.milktea.order.order.service.OrderService;
import com.milktea.order.order.service.SequenceService;
import com.milktea.order.order.vo.PayVo;
import com.milktea.order.product.service.PricingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 拼单服务（T63，A5 · W13）。
 *
 * <p><b>复用 AI 草稿单的「草稿 → 冻结 → 转正式单」范式</b>（任务卡要求）：AI 草稿与拼单池
 * 是同一种东西——临时聚合、可能过期、最终转成正式订单。故生命周期服务共用同一套思路，
 * 且拼单同样经 {@link PricingService} 这个唯一算价入口生成正式单。</p>
 *
 * <p><b>四类并发 / 异常的处理方式</b>（任务卡逐条对应）：</p>
 * <ol>
 *   <li><b>两人同时改</b>：{@code group_cart.version} 乐观锁。写入一律走
 *       {@code WHERE version = ?} 的 CAS，更新数 0 即「你读到的那份已过期」，
 *       本次不写并报错，让客户端重新拉取。<b>不靠加锁把十个人串行化</b>——
 *       那会让一个人点慢导致其他人全部等待。</li>
 *   <li><b>超时失效</b>：{@code expires_at > NOW()} 直接写进 CAS 的 WHERE 条件，
 *       由数据库裁决而非业务层「先判断再更新」——后者中间有缝隙。
 *       另有定时任务把到期的池批量置 EXPIRED。</li>
 *   <li><b>中途退出</b>：提交空 items 即把自己从池里移除（不是删单，只是移除自己的选品）。</li>
 *   <li><b>发起人不付款</b>：生成的是 PENDING_PAYMENT 订单，由既有的超时关单任务关闭；
 *       拼单池随即置 EXPIRED，<b>不会留下任何有效订单</b>。</li>
 * </ol>
 *
 * <p><b>渠道归属</b>：拼单来自小程序，故订单 source 取 {@code MINI_PROGRAM} 而非新增渠道——
 * 6.5 统计口径只有三渠道分列，新增第四个会让所有历史报表的渠道结构失配。</p>
 *
 * <p><b>隐私</b>：成员标识只写 {@code order_item.member_tag}，不写 customer 表、不进画像。</p>
 */
@Slf4j
@Service
public class GroupCartService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 收单时长默认 30 分钟（与 ai_session 的草稿过期同量级）。 */
    private static final int DEFAULT_MINUTES = 30;
    /** 收单时长上限 120 分钟：拼单是「一起喝」，不是预约明天。 */
    private static final int MAX_MINUTES = 120;
    /** CAS 版本冲突时的重试次数（十人同时改仍可能连续冲突）。 */
    private static final int MAX_CAS_RETRY = 5;

    private final GroupCartMapper groupCartMapper;
    private final PricingService pricingService;
    private final OrderService orderService;
    private final SequenceService sequenceService;

    /** 自然日与到期判定基准：与 T11 流水号、T34 统计同源。 */
    private final Clock clock;

    public GroupCartService(GroupCartMapper groupCartMapper,
                            PricingService pricingService,
                            OrderService orderService,
                            SequenceService sequenceService,
                            @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.groupCartMapper = groupCartMapper;
        this.pricingService = pricingService;
        this.orderService = orderService;
        this.sequenceService = sequenceService;
        this.clock = Clock.system(ZoneId.of(timeZone));
    }

    /**
     * 创建拼单池（无初始选品）。
     *
     * @param customerId 发起人
     * @param request    收单时长
     * @return 新池的 groupUuid
     */
    @Transactional(rollbackFor = Exception.class)
    public String create(Long customerId, GroupCreateRequest request) {
        int minutes = request == null || request.minutes() == null
                ? DEFAULT_MINUTES
                : Math.min(Math.max(request.minutes(), 1), MAX_MINUTES);
        LocalDateTime now = LocalDateTime.now();

        GroupCart cart = new GroupCart();
        cart.setGroupUuid(UUID.randomUUID().toString().replace("-", ""));
        cart.setOwnerId(customerId);
        cart.setSharedDraft("[]");
        cart.setMemberIds("[]");
        cart.setVersion(0L);
        cart.setStatus(GroupCart.STATUS_OPEN);
        cart.setExpiresAt(now.plusMinutes(minutes));
        cart.setCreatedAt(now);
        cart.setUpdatedAt(now);
        groupCartMapper.insert(cart);

        log.info("[T63] group cart created uuid={} owner={} expiresAt={}",
                cart.getGroupUuid(), customerId, cart.getExpiresAt());
        return cart.getGroupUuid();
    }

    /**
     * 提交 / 更新自己的选品（整份替换语义）。
     *
     * <p>提交空 items = 退出拼单（把自己从池里移除）。</p>
     *
     * @param groupUuid 拼单标识
     * @param customerId 当前登录顾客
     * @param request   tag + items
     * @throws BusinessException 1004 拼单不存在 / 已冻结 / 已到期；1001 选品含非法商品规格
     */
    @Transactional(rollbackFor = Exception.class)
    public void submitItems(String groupUuid, Long customerId, GroupItemSubmitRequest request) {
        // 先做计价校验再落库：商品下架 / 规格非法不该被存进池子等到支付时才报错
        List<OrderItemRequest> items = request.safeItems();
        if (!items.isEmpty()) {
            pricingService.calculatePrice(items);
        }

        for (int attempt = 0; attempt < MAX_CAS_RETRY; attempt++) {
            GroupCart cart = requireCart(groupUuid);
            requireOpen(cart);

            List<MemberDraft> drafts = parseDraft(cart.getSharedDraft());
            boolean leaving = request.isLeaving();
            drafts.removeIf(draft -> Objects.equals(draft.customerId(), customerId));
            Set<Long> members = new LinkedHashSet<>(parseMemberIds(cart.getMemberIds()));
            if (leaving) {
                members.remove(customerId);
            } else {
                members.add(customerId);
                drafts.add(new MemberDraft(customerId, request.normalizedTag(), items));
            }

            int affected = groupCartMapper.casUpdateDraft(cart.getId(), cart.getVersion(),
                    JSON.writeValueAsString(drafts), JSON.writeValueAsString(new ArrayList<>(members)));
            if (affected == 1) {
                log.info("[T63] group draft submitted uuid={} customer={} items={} leaving={}",
                        groupUuid, customerId, items.size(), leaving);
                return;
            }
            log.info("[T63] group draft CAS conflict uuid={} attempt={}", groupUuid, attempt + 1);
        }
        throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "拼单正在被其他人修改，请重试");
    }

    /**
     * 发起人冻结拼单（进入待支付）。
     *
     * <p>冻结后他人不可再加入或改单（CAS 的 {@code status = 'OPEN'} 条件保证），
     * 但发起人仍可放弃。</p>
     *
     * @throws BusinessException 1004 不是发起人 / 已被冻结 / 已到期
     */
    @Transactional(rollbackFor = Exception.class)
    public void freeze(String groupUuid, Long customerId) {
        GroupCart cart = requireCart(groupUuid);
        if (!Objects.equals(cart.getOwnerId(), customerId)) {
            // 不泄露「是否已冻结」等信息，只报「只有发起人可以冻结」
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "只有发起人可以冻结拼单");
        }
        int affected = groupCartMapper.casFreeze(cart.getId(), cart.getVersion(), customerId);
        if (affected != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "拼单已冻结或已到期，无法再次冻结");
        }
        log.info("[T63] group cart frozen uuid={} owner={}", groupUuid, customerId);
    }

    /**
     * 发起人支付：冻结的池 → 一张正式订单（一个取餐码 + N 个订单项）。
     *
     * <p><b>金额由后端重算</b>（验收项）：下单那一刻重新走 {@link PricingService}，
     * 池里存的只是 productId 与规格，不存也不信任任何金额。冻结期间改价会即时生效。</p>
     *
     * <p><b>只生成一张订单、一个取餐码</b>：取餐码规则完全沿用 6.2 的当日流水，
     * 团单只是「多个订单项共用一个码」，不改动流水规则本身。</p>
     *
     * <p><b>重复提交不会生成第二张订单</b>：{@code FROZEN → CONVERTING} 是 CAS 转移，
     * 第二个并发请求影响 0 行。</p>
     *
     * @return 支付结果（含取餐码）
     * @throws BusinessException 1004 未冻结 / 已转单 / 池为空；1002 商品已下架；1006 店铺暂停
     */
    @Transactional(rollbackFor = Exception.class)
    public PayVo pay(String groupUuid, Long customerId) {
        GroupCart cart = requireCart(groupUuid);
        if (!Objects.equals(cart.getOwnerId(), customerId)) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "只有发起人可以支付");
        }
        if (groupCartMapper.casStartConvert(cart.getId(), customerId) != 1) {
            // 状态不是 FROZEN：可能已支付过、或已到期失效
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "拼单未冻结或已处理，请刷新查看");
        }

        List<MemberDraft> drafts = parseDraft(cart.getSharedDraft());
        if (drafts.isEmpty()) {
            throw new BusinessException(ErrorCode.DRAFT_EMPTY);
        }

        // 展平全部成员的选品，并记住每一杯属于谁（出餐时喊「003 王工」，T64 用）
        List<TaggedItemRequest> tagged = new ArrayList<>();
        for (MemberDraft draft : drafts) {
            for (OrderItemRequest item : draft.items() == null ? List.<OrderItemRequest>of() : draft.items()) {
                tagged.add(new TaggedItemRequest(item, draft.tag()));
            }
        }
        if (tagged.isEmpty()) {
            throw new BusinessException(ErrorCode.DRAFT_EMPTY);
        }

        Long orderId = orderService.createGroupOrder(tagged, cart);
        groupCartMapper.markConverted(cart.getId(), orderId);

        // 复用既有支付通道（Mock / 微信），支付细节与普通下单完全一致
        PayVo paid = orderService.pay(orderId, customerId);
        log.info("[T63] group cart converted uuid={} orderId={} pickupCode={} items={}",
                groupUuid, orderId, paid.getPickupCode(), tagged.size());
        return paid;
    }

    /**
     * 发起人放弃拼单（主动失效，不生成订单）。
     *
     * @throws BusinessException 1004 非发起人 / 已转单
     */
    @Transactional(rollbackFor = Exception.class)
    public void abandon(String groupUuid, Long customerId) {
        GroupCart cart = requireCart(groupUuid);
        if (!Objects.equals(cart.getOwnerId(), customerId)) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "只有发起人可以结束拼单");
        }
        groupCartMapper.markExpired(cart.getId());
        log.info("[T63] group cart abandoned uuid={} owner={}", groupUuid, customerId);
    }

    /**
     * 查看拼单池（含实时计价）。
     *
     * <p><b>金额永远现算</b>，不读池里存的任何价格——这也让「加料期间改价」自动生效。
     * 无法计价的项（商品已下架）会被剔除并在 {@code invalidTip} 里点名，
     * 而不是让整单因一杯下架商品而无法支付。</p>
     */
    @Transactional(readOnly = true)
    public GroupCartVo view(String groupUuid, Long customerId) {
        GroupCart cart = requireCart(groupUuid);
        List<MemberDraft> drafts = parseDraft(cart.getSharedDraft());

        boolean expired = isExpired(cart);
        List<GroupCartVo.Member> members = new ArrayList<>(drafts.size());
        List<String> invalid = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int cups = 0;

        for (MemberDraft draft : drafts) {
            List<OrderItemRequest> items = draft.items() == null ? List.of() : draft.items();
            if (items.isEmpty()) {
                continue;
            }
            // 逐人计价：计价引擎不接受「部分失败」，故按人隔离——
            // 一个人的商品下架，不该让其他人的杯子一起算不出来
            List<GroupCartVo.Item> priced = new ArrayList<>(items.size());
            for (OrderItemRequest item : items) {
                try {
                    PricedItem pi = pricingService.calculatePrice(item);
                    total = total.add(pi.getItemAmount());
                    cups += pi.getQuantity() == null ? 0 : pi.getQuantity();
                    priced.add(toItemVo(pi));
                } catch (BusinessException e) {
                    invalid.add(describeItem(item));
                }
            }
            if (!priced.isEmpty()) {
                members.add(new GroupCartVo.Member(draft.customerId(), draft.tag(), priced));
            }
        }

        boolean canFreeze = !expired
                && GroupCart.STATUS_OPEN.equals(cart.getStatus())
                && Objects.equals(cart.getOwnerId(), customerId);
        // joined 由服务端判定：池里只有一人时客户端无法区分「那是我」还是「那是别人」
        boolean joined = drafts.stream().anyMatch(draft -> Objects.equals(draft.customerId(), customerId));
        boolean isOwner = Objects.equals(cart.getOwnerId(), customerId);
        // 支付 / 放弃只在「已冻结且我是发起人」时开放：非发起人点了只会被后端 1004 挡回，
        // 与其让用户点了才报错，不如根本不给按钮。
        boolean canPay = isOwner && GroupCart.STATUS_FROZEN.equals(cart.getStatus()) && !expired;

        return new GroupCartVo(
                cart.getGroupUuid(),
                cart.getOwnerId(),
                cart.getStatus(),
                expired,
                joined,
                canFreeze,
                canPay,
                cart.getExpiresAt() == null ? null : cart.getExpiresAt().format(DATETIME_FMT),
                MoneyUtils.format(total),
                cups,
                invalid.isEmpty() ? null : "以下商品已下架或规格失效，已暂不计入：" + String.join("、", invalid),
                members);
    }

    /**
     * 定时任务用：把到期仍未转单的池批量置失效。
     *
     * <p>「不生成订单」由 {@link #pay} 依赖 {@code status = 'FROZEN'} 保证：
     * 一旦置为 EXPIRED，支付路径就再也走不通（验收项）。</p>
     *
     * @return 本次置失效的条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int expireOverdue() {
        List<Long> ids = groupCartMapper.selectExpirable(200);
        int count = 0;
        for (Long id : ids) {
            count += groupCartMapper.markExpired(id);
        }
        if (count > 0) {
            log.info("[T63] expired {} overdue group carts", count);
        }
        return count;
    }

    /** 到期判定：以 {@code expiresAt} 为准，状态列可能还没被定时任务刷新。 */
    private boolean isExpired(GroupCart cart) {
        return cart.getExpiresAt() != null && cart.getExpiresAt().isBefore(LocalDateTime.now());
    }

    /** 只有 OPEN 且未到期才允许改动池子（验收项「冻结后不可加入」）。 */
    private void requireOpen(GroupCart cart) {
        if (!GroupCart.STATUS_OPEN.equals(cart.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(),
                    GroupCart.STATUS_EXPIRED.equals(cart.getStatus()) ? "拼单已结束" : "拼单已冻结，无法再添加商品");
        }
        if (isExpired(cart)) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "拼单已超时结束");
        }
    }

    private GroupCart requireCart(String groupUuid) {
        GroupCart cart = groupCartMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GroupCart>()
                        .eq(GroupCart::getGroupUuid, groupUuid)
                        .last("LIMIT 1"));
        if (cart == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "拼单不存在或已结束");
        }
        return cart;
    }

    private List<MemberDraft> parseDraft(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            MemberDraft[] arr = JSON.readValue(json, MemberDraft[].class);
            return arr == null ? new ArrayList<>() : new ArrayList<>(List.of(arr));
        } catch (Exception e) {
            log.warn("[T63] 共享草稿解析失败，按空池处理：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<Long> parseMemberIds(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            Long[] arr = JSON.readValue(json, Long[].class);
            return arr == null ? new ArrayList<>() : new ArrayList<>(List.of(arr));
        } catch (Exception e) {
            log.warn("[T63] 成员列表解析失败，按空处理：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private GroupCartVo.Item toItemVo(PricedItem pi) {
        return new GroupCartVo.Item(pi.getProductId(), pi.getProductName(),
                pi.getQuantity() == null ? 0 : pi.getQuantity(),
                summarizeOptions(pi), MoneyUtils.format(pi.getUnitPrice()), MoneyUtils.format(pi.getItemAmount()));
    }

    /** 规格一行摘要（仅展示，不作核对依据——核对看 order_item.options_snapshot）。 */
    private String summarizeOptions(PricedItem pi) {
        if (pi.getOptions() == null || pi.getOptions().isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>(pi.getOptions().size());
        for (var option : pi.getOptions()) {
            names.add(option.getOptionName());
        }
        return String.join(" · ", names);
    }

    /** 无法计价的项给店长看的名字（尽量可读，不回显 id 堆砌）。 */
    private String describeItem(OrderItemRequest item) {
        return "商品#" + item.getProductId() + "×" + item.getQuantity();
    }
}