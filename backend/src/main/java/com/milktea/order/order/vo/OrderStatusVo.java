package com.milktea.order.order.vo;

import java.io.Serializable;

/**
 * 轮询专用轻量状态响应（LLD 3.3 {@code GET /api/customer/orders/{id}/status}）：
 * {@code {status, pickupCode, seq}}——3 秒间隔轮询，响应体刻意最小化（< 1KB）。
 *
 * @param status     订单状态原值（前端按 4.1 状态机映射文案：PAID→排队中第 N 位、PREPARING→制作中、COMPLETED→请取餐）
 * @param pickupCode 取餐码（未分配为 null）
 * @param seq        当前排队序号 = 该单之前处于 PAID/PREPARING 的单数；前端展示「排队第 seq+1 位」
 */
public record OrderStatusVo(String status, String pickupCode, long seq) implements Serializable {
}
