package com.milktea.order.statistics.vo;

/**
 * 订单流水明细行（T34，LLD 3.5.4 {@code GET /api/admin/stats/orders}）。
 *
 * <p>供商家对账：暴露状态、渠道、取餐码、金额、时间组与作废原因，异常单（作废 / 超时关闭）
 * 一并列出，不做隐藏。时间戳统一格式化为 {@code yyyy-MM-dd HH:mm:ss}，未发生的阶段为 {@code null}。</p>
 *
 * @param orderId     订单 id
 * @param orderNo     订单号
 * @param status      状态（PENDING_PAYMENT / PAID / PREPARING / COMPLETED / CLOSED / VOIDED）
 * @param source      渠道（MINI_PROGRAM / AI / COUNTER）
 * @param pickupCode  取餐码；未支付为 null
 * @param totalAmount 订单金额（快照，两位小数字符串）
 * @param createdAt   下单时间
 * @param paidAt      支付时间
 * @param startedAt   开始制作时间
 * @param completedAt 出餐时间
 * @param closedAt    超时关闭时间
 * @param voidedAt    作废时间
 * @param voidReason  作废原因
 */
public record StatsOrderRowVo(
        Long orderId,
        String orderNo,
        String status,
        String source,
        String pickupCode,
        String totalAmount,
        String createdAt,
        String paidAt,
        String startedAt,
        String completedAt,
        String closedAt,
        String voidedAt,
        String voidReason
) {
}
