package com.milktea.order.queue.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 队列预估聚合查询（T46，LLD 11.6）。
 *
 * <p><b>口径单点维护</b>（同 statistics 包约定）：队列口径与工作量口径收敛为常量 SQL 片段，
 * 内核的全部取数复用同一份定义，杜绝多处各写一份导致口径分叉。</p>
 *
 * <ul>
 *   <li>{@link #IN_QUEUE}：队列口径 = 待制作（{@code PAID}）+ 制作中（{@code PREPARING}），
 *       对应 LLD 11.6 的 {@code n_wait} / {@code n_prep}。已完成 / 超时关闭 / 已作废不在队列内。</li>
 *   <li>{@link #CUPS}：工作量口径 = 主饮品件数（{@code order_item.quantity} 合计），
 *       与 SRS 6.5「售出杯数 = 主饮品件数（加料、规格不另计）」同源——加料与规格是
 *       {@code options_snapshot} 内的选项，不产生额外的 {@code order_item} 行，故件数天然只算主饮品。</li>
 *   <li>{@link #QUEUE_BASE}：件数口径必须落在一行一件上，故 orders 与 order_item 必 JOIN。</li>
 * </ul>
 *
 * <p><b>只读</b>：两条查询均为 SELECT，不写 {@code orders}、不触发状态迁移（LLD 11.6）。</p>
 */
public interface QueueMapper {

    /** 队列口径：待制作 + 制作中（LLD 11.6 {@code n_wait} / {@code n_prep}）。 */
    String IN_QUEUE = " o.status IN ('PAID', 'PREPARING') ";

    /** 工作量口径：主饮品件数（杯）。 */
    String CUPS = "COALESCE(SUM(oi.quantity), 0)";

    /**
     * 队列取数基座：orders ⋈ order_item + 队列口径。
     *
     * <p>用 {@code LEFT JOIN} 而非 {@code INNER JOIN}：件数口径需要 {@code order_item}，
     * 但订单数口径不应因「（异常情况下）订单无明细行」而漏计；{@code SUM} 对 NULL 自动忽略，
     * 故 LEFT JOIN 下两种口径同时正确。</p>
     */
    String QUEUE_BASE = " FROM orders o LEFT JOIN order_item oi ON oi.order_id = o.id WHERE" + IN_QUEUE;

    /**
     * 全店队列快照：待制作 / 制作中各自的订单数与件数。
     *
     * <p>订单数用 {@code COUNT(DISTINCT CASE ... o.id END)}——JOIN 后一单会展开成多行，
     * 直接 {@code COUNT(*)} 会把「一单多件」重复计成多单。</p>
     *
     * @return {@code {waitingOrders, preparingOrders, waitingCups, preparingCups}}
     */
    @Select("SELECT "
            + "COUNT(DISTINCT CASE WHEN o.status = 'PAID' THEN o.id END) AS waitingOrders, "
            + "COUNT(DISTINCT CASE WHEN o.status = 'PREPARING' THEN o.id END) AS preparingOrders, "
            + "COALESCE(SUM(CASE WHEN o.status = 'PAID' THEN oi.quantity ELSE 0 END), 0) AS waitingCups, "
            + "COALESCE(SUM(CASE WHEN o.status = 'PREPARING' THEN oi.quantity ELSE 0 END), 0) AS preparingCups"
            + QUEUE_BASE)
    Map<String, Object> selectQueueTotals();

    /**
     * 指定订单<b>之前</b>的队列工作量：队列内、且排在该单之前的订单数与主饮品件数。
     *
     * <p>排序键与商家看板一致（{@code paid_at} 升序、同刻按 {@code id} 升序），因此
     * {@code aheadOrders + 1} 恰为该单在队列中的序号（{@link #selectQueueTotals()} 的
     * 全店工作量则等价于「此刻新加入队列」的单之前的工作量）。</p>
     *
     * @param paidAt     该单支付时间（队列内订单必有值）
     * @param orderId    该单主键（同刻支付时的次级排序键）
     * @return {@code {aheadOrders, aheadCups}}
     */
    @Select("SELECT COUNT(DISTINCT o.id) AS aheadOrders, " + CUPS + " AS aheadCups"
            + QUEUE_BASE
            + "AND (o.paid_at < #{paidAt} OR (o.paid_at = #{paidAt} AND o.id < #{orderId}))")
    Map<String, Object> selectAheadOfWork(@Param("paidAt") LocalDateTime paidAt,
                                          @Param("orderId") Long orderId);
}
