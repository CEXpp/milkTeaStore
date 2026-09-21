package com.milktea.order.statistics.vo;

/**
 * 渠道分布行（T34，LLD 3.5.4 summary.channel）。
 *
 * <p>{@code source} 三分见 {@link com.milktea.order.order.entity.Order#SOURCE_MINI_PROGRAM} 等
 * （SRS 6.5「渠道分布：按订单 source 维度统计订单数与金额」）。</p>
 *
 * @param source     渠道：MINI_PROGRAM / AI / COUNTER
 * @param orderCount 该渠道订单数（含作废，与概览订单数同口径，故各渠道之和 = 概览订单数）
 * @param amount     该渠道金额（剔除作废，与营业额同口径，故各渠道之和 = 概览营业额）
 */
public record StatsChannelVo(
        String source,
        long orderCount,
        String amount
) {
}
