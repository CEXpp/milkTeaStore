package com.milktea.order.statistics.vo;

import java.util.List;

/**
 * 账台概览（T34，LLD 3.5.4 {@code GET /api/admin/stats/summary}）。
 *
 * <p>口径见 SRS 6.5：营业额按支付完成时间归属当日、作废单以退款额单列扣减、超时关闭单不计入。</p>
 *
 * @param date         统计归属日（yyyy-MM-dd）
 * @param totalAmount  营业额 = 当日已支付实付（剔除作废）之和，两位小数字符串
 * @param orderCount   订单数 = 当日进入过「已支付及以后」的订单数（含作废、不含超时关闭）
 * @param cupCount     售出杯数 = 主饮品件数（加料与规格不另计；作废单已退款，不计入）
 * @param refundAmount 退款额 = 当日作废单实付合计（单列，不与营业额混算）
 * @param channel      渠道分布（按 orders.source 三分）
 */
public record StatsSummaryVo(
        String date,
        String totalAmount,
        long orderCount,
        long cupCount,
        String refundAmount,
        List<StatsChannelVo> channel
) {
}
