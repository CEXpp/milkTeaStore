package com.milktea.order.order.dto;

/**
 * 带成员标识的订单项（T63 拼单转正式单用）。
 *
 * <p>把「要什么」与「是谁的」绑在一起：团单里同一个商品可能被多人各点一杯，
 * 光有 {@link OrderItemRequest} 无法在展开成订单项时保住归属关系，
 * 而归属正是「出餐喊 003 王工」的依据。</p>
 *
 * <p>仍然<b>不含任何价格字段</b>——金额一律由 {@code PricingService} 计算
 * （SRS 约束三原则「价格一律后端计算」）。</p>
 *
 * @param item      计价输入
 * @param memberTag 成员标识（如「003 王工」）；非拼单场景为 null
 */
public record TaggedItemRequest(OrderItemRequest item, String memberTag) {
}