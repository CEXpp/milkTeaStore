package com.milktea.order.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mock 支付实现（LLD 5.1）：练手期激活渠道——确认即成功，不产生真实资金流动。
 *
 * <p>交易号形如 {@code MOCK-<32 位十六进制>}（{@code MOCK-} + UUID 去连字符简写），
 * 写入 {@code orders.transaction_id}，真店期由微信支付回调流水号取代。</p>
 */
@Slf4j
@Component
public class MockPaymentProvider implements PaymentProvider {

    /** 渠道标识；配置 {@code payment.active-channel=mock}（匹配不区分大小写）。 */
    public static final String CHANNEL = "MOCK";

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public PayResult pay(Long orderId, BigDecimal amount) {
        // 练手期 Mock 语义：确认即成功
        String transactionId = CHANNEL + "-" + UUID.randomUUID().toString().replace("-", "");
        log.info("[T12] Mock 支付成功 orderId={} amount={} transactionId={}", orderId, amount, transactionId);
        return PayResult.success(transactionId);
    }
}
