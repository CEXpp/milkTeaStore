package com.milktea.order.order.event;

/**
 * 实时事件类型（T43，LLD 11.1「事件结构」）：取值即 SSE {@code event:} 行内容。
 *
 * <ul>
 *   <li>{@link #ORDER_STATUS_CHANGED} 订单状态变更——<b>六态全部发布</b>
 *       （PENDING_PAYMENT / PAID / PREPARING / COMPLETED / CLOSED / VOIDED）；</li>
 *   <li>{@link #PICKUP_READY} 出餐就绪——进入 COMPLETED 时在状态变更之外追加一条，
 *       供 11.4「双信道取餐提醒」（T44/T45）直接消费，避免下游再自行判定状态。</li>
 * </ul>
 *
 * <p>LLD 11.1 示例中的 {@code QUEUE_UPDATED} 属队列预估（11.6 / T46）的推送负载，
 * 本任务只负责订单状态通道，不产生该事件；发布器已按「一个事件体 + 顾客维度路由」设计，
 * T46 接入时无需改动通道设施。</p>
 */
public enum OrderEventType {

    /** 订单状态变更（六态通用）。 */
    ORDER_STATUS_CHANGED,

    /** 出餐就绪（COMPLETED）。 */
    PICKUP_READY
}
