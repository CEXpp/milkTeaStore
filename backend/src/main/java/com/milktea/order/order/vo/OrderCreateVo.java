package com.milktea.order.order.vo;

import java.util.List;

/**
 * 创建订单响应（LLD 3.3）：状态 PENDING_PAYMENT，金额一律为两位小数字符串。
 *
 * @param id          订单 id
 * @param orderNo     订单号（yyMMdd + 5 位序列）
 * @param status      初始状态 PENDING_PAYMENT
 * @param totalAmount 应付总额（两位小数字符串）
 * @param expireAt    支付截止时间（yyyy-MM-dd HH:mm:ss，createdAt + order.payment-timeout-minutes）
 * @param items       订单项快照
 */
public record OrderCreateVo(
        Long id,
        String orderNo,
        String status,
        String totalAmount,
        String expireAt,
        List<OrderItemVo> items
) {
}
