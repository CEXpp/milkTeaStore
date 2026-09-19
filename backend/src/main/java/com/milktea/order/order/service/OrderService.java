package com.milktea.order.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.common.util.MoneyUtils;
import com.milktea.order.order.dto.OrderCreateRequest;
import com.milktea.order.order.dto.OptionSnapshot;
import com.milktea.order.order.dto.PricedItem;
import com.milktea.order.order.dto.PricingResult;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderItem;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.mapper.OrderItemMapper;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.order.vo.CounterOrderVo;
import com.milktea.order.order.vo.OrderCreateVo;
import com.milktea.order.order.vo.OrderItemVo;
import com.milktea.order.order.vo.PayVo;
import com.milktea.order.payment.PayResult;
import com.milktea.order.payment.PaymentService;
import com.milktea.order.product.service.PricingService;
import com.milktea.order.shop.entity.ShopConfig;
import com.milktea.order.shop.mapper.ShopConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 订单服务：订单生命周期编排（下单 T10 + 支付 T12）。
 *
 * <p><b>下单（T10）</b>：① 暂停接单校验 → ② 调 {@link PricingService} 实时计价（唯一算价入口，
 * 同时校验商品上架与规格合法性）→ ③ 经 {@link SequenceService} 取订单号（T11 统一发放口）→
 * ④ 单事务写 orders + order_item（含规格快照）→ ⑤ 组装响应（含支付截止时间）。</p>
 *
 * <p><b>支付（T12）</b>：查单 → 归属校验（1005）→ 状态校验（PENDING_PAYMENT，越界 1004）→ 经
 * {@link PaymentService} 按渠道路由支付 → <b>同一事务内</b>推进 PAID、写
 * {@code pay_channel / transaction_id / paid_at}、分配取餐码（T11）。状态推进用条件更新
 * （{@code WHERE status = 'PENDING_PAYMENT'}）实现：顾客双击支付时两条请求并发到达，
 * 只有一个能更新成功，另一次抛 1004；失败事务整体回滚，连同已分配的取餐码一并回退
 * （不浪费当日流水，符合 LLD 4.2「取号全程在支付事务内」）。</p>
 *
 * <p>订单号格式见 LLD 4.2：yyMMdd + 5 位序列；快照规则见 LLD 2.4，单价 / 金额 / 规格名
 * 在下单瞬间锁定，后续改价不影响历史订单。</p>
 *
 * <p>域边界（HLD 2.3）：支付域只提供策略与路由，订单表读写留在本域；
 * 店铺暂停开关不影响已下单订单（需求规格 4.6），故支付流程不做暂停校验。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String CONFIG_PAUSED = "paused";

    private final PricingService pricingService;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SequenceService sequenceService;
    private final ShopConfigMapper shopConfigMapper;
    private final PaymentService paymentService;

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

        AuthContext.Principal principal = AuthContext.get();
        Order order = insertOrder(priced, Order.SOURCE_MINI_PROGRAM,
                principal == null ? null : principal.getCustomerId(), request.getRemark(), false);

        return toVo(order, priced);
    }

    /**
     * 柜台人工点单（T14/T20，LLD 3.5 {@code POST /api/admin/counter-orders}）：
     * 结构同顾客下单 items，创建即直接 PAID（收款当面完成）、分配取餐码、customer_id 为 NULL。
     *
     * <p>柜台为商家人工操作，不受「暂停接单」开关限制——该开关面向顾客端全渠道
     * （SRS 4.6：开启后顾客端禁止新下单），存量订单与柜台服务不受影响。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public CounterOrderVo createCounterOrder(OrderCreateRequest request) {
        PricingResult priced = pricingService.calculatePrice(request.getItems());
        Order order = insertOrder(priced, Order.SOURCE_COUNTER, null, request.getRemark(), true);
        log.info("[T14] 柜台单创建成功 orderId={} orderNo={} pickupCode={} amount={}",
                order.getId(), order.getOrderNo(), order.getPickupCode(), order.getTotalAmount());
        return new CounterOrderVo(order.getId(), order.getOrderNo(), order.getPickupCode(),
                MoneyUtils.format(order.getTotalAmount()));
    }

    /**
     * 建单共性（订单号发放 + orders / order_item 落库，含规格快照）。
     *
     * @param paid true 表示创建即已支付（柜台单：同时分配取餐码并写支付字段）
     */
    private Order insertOrder(PricingResult priced, String source, Long customerId, String remark, boolean paid) {
        // 订单号统一由 T11 SequenceService 发放（daily_seq 行锁递增，同事务内取号）
        String orderNo = sequenceService.nextOrderNo();
        LocalDateTime now = LocalDateTime.now();

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setSource(source);
        order.setStatus(paid ? OrderStatus.PAID.name() : OrderStatus.PENDING_PAYMENT.name());
        order.setCustomerId(customerId);
        order.setTotalAmount(priced.getTotalAmount());
        order.setRemark(remark);
        if (paid) {
            // 柜台当面收款：创建即 PAID，直接分配取餐码（与小程序单共用当日流水）
            order.setPickupCode(sequenceService.nextPickupCode());
            order.setPayChannel(Order.PAY_CHANNEL_COUNTER);
            order.setPaidAt(now);
        }
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        orderMapper.insert(order);

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
        }
        return order;
    }

    /**
     * 顾客支付订单（LLD 3.3 {@code POST /api/customer/orders/{id}/pay}）。
     *
     * @param orderId    订单主键
     * @param customerId 当前登录顾客（JWT 身份）
     * @return 支付结果响应：{@code {id, status, pickupCode, paidAt, payChannel}}
     * @throws BusinessException 1005 非本人订单；1004 订单不存在 / 状态不可支付 / 并发重复支付
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PayVo pay(Long orderId, Long customerId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "订单不存在");
        }
        // 归属校验：他人订单一律 1005，不泄露订单内容
        if (!Objects.equals(order.getCustomerId(), customerId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_BELONG);
        }
        // 状态机硬校验（T14，LLD 4.1）：仅 PENDING_PAYMENT 可支付成功；重复支付/已关闭/已作废均为 1004
        OrderStateMachine.next(OrderStateMachine.parse(order.getStatus()), OrderStateMachine.Event.PAY_SUCCESS);

        // 策略支付（Mock 立即成功；真店切微信只改配置与实现）
        PayResult result = paymentService.pay(orderId, order.getTotalAmount());
        if (!result.isSuccess()) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "支付失败：" + result.getMessage());
        }

        // 同事务落库：状态推进 + 支付三字段 + 取餐码（条件更新防并发双击）
        LocalDateTime now = LocalDateTime.now();
        String pickupCode = sequenceService.nextPickupCode();
        String channel = paymentService.activeChannel();
        int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, OrderStatus.PENDING_PAYMENT.name())
                .set(Order::getStatus, OrderStatus.PAID.name())
                .set(Order::getPayChannel, channel)
                .set(Order::getTransactionId, result.getTransactionId())
                .set(Order::getPaidAt, now)
                .set(Order::getPickupCode, pickupCode)
                .set(Order::getUpdatedAt, now));
        if (updated == 0) {
            // 并发双击场景：状态已被另一请求推进；本事务回滚（含取餐码），本次返回 1004
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }

        log.info("[T12] 订单支付成功 orderId={} orderNo={} channel={} pickupCode={} transactionId={}",
                orderId, order.getOrderNo(), channel, pickupCode, result.getTransactionId());
        return PayVo.from(orderMapper.selectById(orderId));
    }

    /**
     * 门店暂停接单校验（LLD 3.2 → 1006）。
     */
    private void checkShopOpen() {
        ShopConfig config = shopConfigMapper.selectOne(new LambdaQueryWrapper<ShopConfig>()
                .eq(ShopConfig::getConfigKey, CONFIG_PAUSED));
        if (config != null && Boolean.parseBoolean(config.getConfigValue())) {
            throw new BusinessException(ErrorCode.SHOP_PAUSED);
        }
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
