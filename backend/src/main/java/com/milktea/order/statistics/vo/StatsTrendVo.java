package com.milktea.order.statistics.vo;

/**
 * 逐日趋势行（T34，LLD 3.5.4 {@code GET /api/admin/stats/trend}）。
 *
 * <p>SRS 6.5「趋势：近 7 日逐日营业额与订单数」。无单日由服务层补 {@code 0 / "0.00"}，
 * 保证日期轴连续（前端折线图不会断点）。</p>
 *
 * @param date       日期（yyyy-MM-dd）
 * @param orderCount 当日订单数（含作废，不含超时关闭）
 * @param amount     当日营业额（剔除作废）
 */
public record StatsTrendVo(
        String date,
        long orderCount,
        String amount
) {
}
