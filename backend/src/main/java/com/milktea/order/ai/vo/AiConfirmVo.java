package com.milktea.order.ai.vo;

/**
 * AI 草稿转订单响应 data（LLD 3.4）。
 *
 * <p>只回订单标识与金额，前端拿到 {@code orderId} 后立即连发 pay 完成支付闭环——
 * AI 侧不提供任何支付工具，支付动作永远由用户在前端触发（SRS 约束三原则第三条）。</p>
 *
 * @param orderId     订单主键（供前端连发 pay）
 * @param orderNo     订单号（yyMMdd + 5 位序列）
 * @param totalAmount 应付总额（两位小数字符串）
 */
public record AiConfirmVo(
        Long orderId,
        String orderNo,
        String totalAmount
) {
}
