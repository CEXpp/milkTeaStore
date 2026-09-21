package com.milktea.order.statistics.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 账台统计聚合查询（T34，LLD 3.5.4 / SRS 6.5）。
 *
 * <p><b>口径单点维护</b>（statistics 包设计约定「口径常量化，单点维护」）：本接口把三个统计口径
 * 收敛为常量 SQL 片段，四个接口全部复用，杜绝多处各写一份导致口径分叉。</p>
 *
 * <p>三种口径（依据 SRS 6.5 表 6-2）：</p>
 * <ol>
 *   <li>{@link #PAID_SINCE}「已支付及以后（含作废）」——用于<b>订单数</b>与<b>渠道订单数</b>。
 *       SRS 明确「订单数：当日进入过已支付及以后状态的订单数（含作废，不含超时关闭）」。</li>
 *   <li>{@link #VALID_SINCE}「有效已支付（剔除作废）」——用于<b>营业额</b>、<b>杯数</b>、
 *       <b>渠道金额</b>、<b>排行金额与件数</b>。作废单已退款，钱与货都不该计入成交量。</li>
 *   <li>{@link #NET_AMOUNT} + {@link #REFUND_AMOUNT} —— 同一批「已支付及以后」订单里，
 *       把作废单的实付单列为退款额、其余计入营业额（SRS 6.5「作废订单以退款额单列扣减」；
 *       orders 表无退款额列，故退款额 = 作废单的 {@code total_amount}）。</li>
 * </ol>
 *
 * <p><b>归属日</b>：一律按 {@code paid_at}（支付完成时间）划分自然日（SRS 6.5「按支付完成时间归属当日」），
 * 超时关闭单没有 {@code paid_at}，天然不落入任何一天——满足「超时关闭单不计入营业额与订单数」。</p>
 *
 * <p><b>不加缓存</b>（T34 卡）：单店量级下直接 SQL 聚合，杯数经 {@code JOIN order_item} 写透计算，
 * 避免缓存与订单状态变更之间出现口径漂移。</p>
 */
public interface StatsMapper {

    /** 口径①：当日「已支付及以后（含作废）」订单。 */
    String PAID_SINCE = " o.paid_at >= #{start} AND o.paid_at < #{end} "
            + "AND o.status IN ('PAID', 'PREPARING', 'COMPLETED', 'VOIDED') ";

    /** 口径②：当日「有效已支付」（剔除作废）订单。 */
    String VALID_SINCE = " o.paid_at >= #{start} AND o.paid_at < #{end} "
            + "AND o.status IN ('PAID', 'PREPARING', 'COMPLETED') ";

    /** 口径①内：非作废单实付合计（= 营业额）。 */
    String NET_AMOUNT = "COALESCE(SUM(CASE WHEN o.status = 'VOIDED' THEN 0 ELSE o.total_amount END), 0)";

    /** 口径①内：作废单实付合计（= 退款额）。 */
    String REFUND_AMOUNT = "COALESCE(SUM(CASE WHEN o.status = 'VOIDED' THEN o.total_amount ELSE 0 END), 0)";

    /**
     * 概览三数：订单数（含作废）、营业额（扣作废）、退款额（单列）。
     *
     * @return {@code {orderCount, totalAmount, refundAmount}}
     */
    @Select("SELECT COUNT(*) AS orderCount, " + NET_AMOUNT + " AS totalAmount, "
            + REFUND_AMOUNT + " AS refundAmount FROM orders o WHERE" + PAID_SINCE)
    Map<String, Object> selectDayTotals(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 渠道分布：按 {@code orders.source} 三分（SRS 6.5）。
     *
     * <p>订单数取口径①（含作废），金额取口径②（净额）——这样「各渠道订单数之和 = 概览订单数」
     * 且「各渠道金额之和 = 概览营业额」，与 LLD 3.5.4 的示例数字自洽。</p>
     *
     * @return 每行 {@code {source, orderCount, amount}}，按 source 升序
     */
    @Select("SELECT o.source AS source, COUNT(*) AS orderCount, " + NET_AMOUNT + " AS amount "
            + "FROM orders o WHERE" + PAID_SINCE + "GROUP BY o.source ORDER BY o.source")
    List<Map<String, Object>> selectDayChannels(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 当日售出杯数：主饮品件数（加料、规格不另计，SRS 6.5），口径②。
     *
     * @return {@code {cupCount}}
     */
    @Select("SELECT COALESCE(SUM(oi.quantity), 0) AS cupCount "
            + "FROM order_item oi JOIN orders o ON o.id = oi.order_id WHERE" + VALID_SINCE)
    Map<String, Object> selectDayCupCount(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 近 N 日逐日趋势（SRS 6.5「近 7 日逐日营业额与订单数」）。
     *
     * <p>只返回有单的日期，缺口由服务层补零（图表需要连续日期轴）。日期用 {@code DATE_FORMAT}
     * 直接出字符串，避免 JDBC 的 DATE→java.sql.Date 转换差异。</p>
     *
     * @return 每行 {@code {day, orderCount, amount}}，按日升序
     */
    @Select("SELECT DATE_FORMAT(o.paid_at, '%Y-%m-%d') AS day, COUNT(*) AS orderCount, "
            + NET_AMOUNT + " AS amount FROM orders o WHERE" + PAID_SINCE
            + "GROUP BY DATE_FORMAT(o.paid_at, '%Y-%m-%d') ORDER BY day")
    List<Map<String, Object>> selectDailyTrend(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 商品销量排行（SRS 6.5「按件数排序」，口径②）。
     *
     * <p>分组维度为「商品 + 商品名快照」：{@code order_item.product_name} 是下单瞬间的快照
     * （AC-16 快照规则），商品改名后历史单仍是旧名，故按快照名分组、不做名字归并——
     * 宁可多出一行，也不为了让排行「好看」而篡改历史口径。</p>
     *
     * @param limit 取前 N 名
     * @return 每行 {@code {productId, productName, cupCount, amount}}
     */
    @Select("SELECT oi.product_id AS productId, oi.product_name AS productName, "
            + "COALESCE(SUM(oi.quantity), 0) AS cupCount, COALESCE(SUM(oi.item_amount), 0) AS amount "
            + "FROM order_item oi JOIN orders o ON o.id = oi.order_id WHERE" + VALID_SINCE
            + "GROUP BY oi.product_id, oi.product_name ORDER BY cupCount DESC, amount DESC LIMIT #{limit}")
    List<Map<String, Object>> selectProductRanking(@Param("start") LocalDateTime start,
                                                  @Param("end") LocalDateTime end,
                                                  @Param("limit") int limit);
}
