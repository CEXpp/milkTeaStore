package com.milktea.order.payment;

import lombok.Getter;

/**
 * 支付结果（LLD 5.1）：策略接口的统一返回，屏蔽各渠道差异——调用方只认成功与否与交易号。
 *
 * <p>Mock 实现立即返回 {@code success("MOCK-" + UUID 简写)}；真店期微信实现按回调结果返回。</p>
 */
@Getter
public class PayResult {

    /** 是否支付成功。 */
    private final boolean success;

    /** 渠道交易号（写 orders.transaction_id）；失败时为 null。 */
    private final String transactionId;

    /** 失败原因（成功时为 null），用于业务异常文案与排障日志。 */
    private final String message;

    private PayResult(boolean success, String transactionId, String message) {
        this.success = success;
        this.transactionId = transactionId;
        this.message = message;
    }

    /** 支付成功：交易号由渠道实现生成。 */
    public static PayResult success(String transactionId) {
        return new PayResult(true, transactionId, null);
    }

    /** 支付失败：附渠道返回的原因，由订单域转为业务异常。 */
    public static PayResult failure(String message) {
        return new PayResult(false, null, message);
    }
}
