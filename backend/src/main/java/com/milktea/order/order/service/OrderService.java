package com.milktea.order.order.service;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.common.util.MoneyUtils;
import com.milktea.order.order.dto.OrderCreateRequest;
import com.milktea.order.order.dto.OrderItemRequest;
import com.milktea.order.order.dto.OptionSnapshot;
import com.milktea.order.order.dto.PricedItem;
import com.milktea.order.order.dto.PricingResult;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderItem;
import com.milktea.order.order.mapper.DailySeqMapper;
import com.milktea.order.order.mapper.OrderItemMapper;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.order.vo.OrderCreateVo;
import com.milktea.order.order.vo.OrderItemVo;
import com.milktea.order.product.service.PricingService;
import com.milktea.order.shop.entity.ShopConfig;
import com.milktea.order.shop.mapper.ShopConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 下单服务（T10）：创建 PENDING_PAYMENT 状态的小程序订单。
 *
 * <p>全流程：① 暂停接单校验 → ② 调 {@link PricingService} 实时计价（唯一算价入口，
 * 同时校验商品上架与规格合法性）→ ③ 生成订单号（daily_seq 行锁递增）→ ④ 单事务写
 * orders + order_item（含规格快照）→ ⑤ 组装响应（含支付截止时间）。</p>
 *
 * <p>订单号格式见 LLD 4.2：yyMMdd + 5 位序列；快照规则见 LLD 2.4，单价 / 金额 / 规格名
 * 在下单瞬间锁定，后续改价不影响历史订单。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String CONFIG_PAUSED = "paused";

    private final PricingService pricingService;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final DailySeqMapper dailySeqMapper;
    private final ShopConfigMapper shopConfigMapper;

    @Value("${order.payment-timeout-minutes:15}")
    private int paymentTimeoutMinutes;

    /**
     * 创建订单。整段在事务内完成（含订单号流水自增），保证原子性。
     *
     * @param request 下单请求（items + remark）
     * @return 下单响应
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderCreateVo createOrder(OrderCreateRequest request) {
        checkShopOpen();

        // 实时计价：商品下架 / 规格非法会在此抛出 1002 / 1003 / 1001
        PricingResult priced = pricingService.calculatePrice(request.getItems());

        String orderNo = generateOrderNo();
        LocalDateTime now = LocalDateTime.now();

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setSource(Order.SOURCE_MINI_PROGRAM);
        order.setStatus(Order.STATUS_PENDING_PAYMENT);
        AuthContext.Principal principal = AuthContext.get();
        order.setCustomerId(principal == null ? null : principal.getCustomerId());
        order.setTotalAmount(priced.getTotalAmount());
        order.setRemark(request.getRemark());
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        orderMapper.insert(order);

        List<OrderItem> items = new ArrayList<>(priced.getItems().size());
        for (PricedItem pi : priced.getItems()) {
            OrderItem oi = new OrderItem();
            oi.setOrderId(order.getId());
            oi.setProductId(pi.getProductId());
            oi.setProductName(pi.getProductName());
            oi.setBasePrice(pi.getBasePrice());
            oi.setOptionsSnapshot(snapshotJson(pi.getOptions()));
            oi.setQuantity(pi.getQuantity());
            oi.setUnitPrice(pi.getUnitPrice());
            oi.setItemAmount(pi.getItemAmount());
            orderItemMapper.insert(oi);
            items.add(oi);
        }

        return toVo(order, priced);
    }

    /**
     * 门店暂停接单校验（LLD 3.2 → 1006）。
     */
    private void checkShopOpen() {
        ShopConfig config = shopConfigMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShopConfig>()
                        .eq(ShopConfig::getConfigKey, CONFIG_PAUSED));
        if (config != null && Boolean.parseBoolean(config.getConfigValue())) {
            throw new BusinessException(ErrorCode.SHOP_PAUSED);
        }
    }

    /**
     * 订单号生成：daily_seq 行锁递增（LLD 4.2）。UPDATE 影响 0 行则 INSERT 后重试一次。
     */
    private String generateOrderNo() {
        String seqType = DailySeqMapper.TYPE_ORDER_NO;
        LocalDate today = LocalDate.now();
        int updated = dailySeqMapper.increment(today, seqType);
        if (updated == 0) {
            try {
                dailySeqMapper.insertRow(today, seqType);
            } catch (DuplicateKeyException e) {
                // 并发下另一事务已插入该行，忽略后重试递增
                log.debug("daily_seq 行已存在，跳过插入：{} / {}", today, seqType);
            }
            updated = dailySeqMapper.increment(today, seqType);
        }
        Integer seq = dailySeqMapper.selectCurrent(today, seqType);
        if (seq == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR.getCode(), "订单号流水生成失败");
        }
        return today.format(DATE_FMT) + String.format("%05d", seq);
    }

    private String snapshotJson(List<OptionSnapshot> options) {
        if (options == null) {
            return "[]";
        }
        try {
            return JSON.writeValueAsString(options);
        } catch (Exception e) {
            log.warn("规格快照序列化失败：{}", e.getMessage());
            return "[]";
        }
    }

    private OrderCreateVo toVo(Order order, PricingResult priced) {
        List<OrderItemVo> itemVos = new ArrayList<>(priced.getItems().size());
        for (PricedItem pi : priced.getItems()) {
            List<String> optionNames = optionNames(pi.getOptions());
            itemVos.add(new OrderItemVo(
                    pi.getProductName(),
                    pi.getQuantity(),
                    MoneyUtils.format(pi.getUnitPrice()),
                    optionNames,
                    MoneyUtils.format(pi.getItemAmount())));
        }
        String expireAt = order.getCreatedAt()
                .plusMinutes(paymentTimeoutMinutes)
                .format(DATETIME_FMT);
        return new OrderCreateVo(
                order.getId(),
                order.getOrderNo(),
                order.getStatus(),
                MoneyUtils.format(order.getTotalAmount()),
                expireAt,
                itemVos);
    }

    private List<String> optionNames(List<OptionSnapshot> options) {
        if (options == null) {
            return new ArrayList<>();
        }
        List<String> names = new ArrayList<>(options.size());
        for (OptionSnapshot o : options) {
            names.add(o.getOptionName());
        }
        return names;
    }
}
