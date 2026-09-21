package com.milktea.order.statistics.vo;

/**
 * 商品销量排行行（T34，LLD 3.5.4 {@code GET /api/admin/stats/ranking}）。
 *
 * <p>件数与金额取「有效已支付」口径（剔除作废）。分组维度为「商品 + 商品名快照」，
 * 商品改名后历史单仍按旧名成行（AC-16 快照规则）。</p>
 *
 * @param productId   商品 id
 * @param productName 商品名（下单时快照）
 * @param cupCount    件数（主饮品件数，加料与规格不另计）
 * @param amount      金额（该商品的有效已支付小计之和）
 */
public record StatsRankingVo(
        Long productId,
        String productName,
        long cupCount,
        String amount
) {
}
