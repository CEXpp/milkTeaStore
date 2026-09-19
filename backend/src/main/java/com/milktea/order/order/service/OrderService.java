package com.milktea.order.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.order.vo.PayVo;
import com.milktea.order.payment.PayResult;
import com.milktea.order.payment.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 订单服务：订单生命周期编排。
 *
 * <p>T12 落地「Mock 支付」一段（LLD 4.1 迁移表首行 / 5.1 支付流程）：
 * 查单 → 归属校验（1005）→ 状态校验（PENDING_PAYMENT，越界 1004）→ 经
 * {@link PaymentService} 按渠道路由支付 → <b>同一事务内</b>推进 PAID、写
 * {@code pay_channel / transaction_id / paid_at}、分配取餐码（T11）。</p>
 *
 * <p>状态推进用条件更新（{@code WHERE status = 'PENDING_PAYMENT'}）实现：
 * 顾客双击支付时两条请求并发到达，只有一个能更新成功，另一次抛 1004；
 * 失败事务整体回滚，连同已分配的取餐码一并回退（不浪费当日流水，符合 LLD 4.2
 * 「取号全程在支付事务内」）。</p>
 *
 * <p>域边界（HLD 2.3）：支付域只提供策略与路由，订单表读写留在本域；
 * 店铺暂停开关不影响已下单订单（需求规格 4.6），故支付流程不做暂停校验。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final SequenceService sequenceService;
    private final PaymentService paymentService;

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
        // 状态机校验：仅 PENDING_PAYMENT 可支付；重复支付/已关闭/已作废均为 1004
        if (!OrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }

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
}
