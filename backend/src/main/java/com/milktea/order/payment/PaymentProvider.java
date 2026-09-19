package com.milktea.order.payment;

import java.math.BigDecimal;

/**
 * 支付策略接口（LLD 5.1）：渠道路由 + 发起支付两件事，真店切微信只换实现（调用方零改动）。
 *
 * <p>实现类以 {@link org.springframework.stereotype.Component} 注册，由
 * {@link PaymentService} 按配置 {@code payment.active-channel} 选择激活实现。</p>
 */
public interface PaymentProvider {

    /**
     * 渠道标识：取值与 {@code orders.pay_channel} 一致，如 {@code MOCK} / {@code WECHAT}（预留）。
     */
    String channel();

    /**
     * 发起支付。
     *
     * @param orderId 订单主键（渠道流水备注用）
     * @param amount  应付金额（快照总额，不信任前端）
     * @return 支付结果：成功携带渠道交易号，失败携带原因
     */
    PayResult pay(Long orderId, BigDecimal amount);
}
