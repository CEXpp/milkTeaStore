package com.milktea.order.order.event;

import java.time.Instant;

/**
 * SSE 推送事件体（T43，LLD 11.1 事件结构）——不可变数据传输类，按项目约定使用 record。
 *
 * <pre>
 * { "type": "ORDER_STATUS_CHANGED", "orderId": 3001, "status": "PREPARING", "pickupCode": "018", "ts": 1760000000 }
 * </pre>
 *
 * <p><b>出口一致</b>（LLD 11.1）：{@code status / pickupCode} 与 4.4 轮询轻量响应
 * （{@code GET /orders/{id}/status} → {@code {status, pickupCode, seq}}）同源，
 * 前端消费层对 SSE 与轮询两种来源无差别渲染；{@code seq}（排队序号）为查询派生量，
 * 由前端在收到事件后补拉一次轻量状态获得，不随事件下发（事件保持轻量、无额外查询）。</p>
 *
 * <p>{@code ts} 为事件生成时刻的 Unix 秒（LLD 示例口径）。</p>
 */
public record OrderEvent(String type, Long orderId, String status, String pickupCode, long ts) {

    /**
     * 订单状态变更事件。
     *
     * @param orderId    订单主键
     * @param status     变更后的订单状态名（六态之一）
     * @param pickupCode 取餐码（未支付时为 null）
     */
    public static OrderEvent statusChanged(Long orderId, String status, String pickupCode) {
        return new OrderEvent(OrderEventType.ORDER_STATUS_CHANGED.name(), orderId, status, pickupCode, epochSecond());
    }

    /**
     * 出餐就绪事件（COMPLETED）。状态字段固定为 COMPLETED，保证同一单两个事件的状态语义一致。
     */
    public static OrderEvent pickupReady(Long orderId, String pickupCode) {
        return new OrderEvent(OrderEventType.PICKUP_READY.name(), orderId, "COMPLETED", pickupCode, epochSecond());
    }

    private static long epochSecond() {
        return Instant.now().getEpochSecond();
    }
}
