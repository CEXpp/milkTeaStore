package com.milktea.order.group.dto;

import com.milktea.order.order.dto.OrderItemRequest;

import java.util.List;

/**
 * 共享草稿池中单个参与者的选品（落库到 {@code group_cart.shared_draft}）。
 *
 * <p>形状：{@code [{customerId, tag, items:[{productId, optionIds, quantity}]}]}。</p>
 *
 * <p><b>与 {@link OrderItemRequest} 的区别</b>：这里多两个字段——
 * {@code customerId}（谁的选品，用于隔离互不覆盖）与 {@code tag}（给店员看的成员标识）。
 * 计价仍走唯一的 {@code PricingService}，本 record 同样不含任何价格字段。</p>
 *
 * @param customerId 参与者 customer id（唯一键：同一人在池中只占一个元素）
 * @param tag成员标识（自填，如「003 王工」；仅随订单项流转，不入画像）
 * @param items      该参与者的选品
 */
public record MemberDraft(Long customerId, String tag, List<OrderItemRequest> items) {
}