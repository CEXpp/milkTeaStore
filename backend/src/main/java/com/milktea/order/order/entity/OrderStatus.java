package com.milktea.order.order.entity;

/**
 * 订单状态（LLD 4.1）：六态取值与 {@code orders.status} 列注释一致。
 *
 * <ul>
 *   <li>PENDING_PAYMENT 待支付：下单即进入，15 分钟未支付自动关闭；</li>
 *   <li>PAID 已支付待制作：支付成功进入（本态分配取餐码）；</li>
 *   <li>PREPARING 制作中；</li>
 *   <li>COMPLETED 已完成；</li>
 *   <li>CLOSED 超时关闭；</li>
 *   <li>VOIDED 已作废（商家退款）。</li>
 * </ul>
 *
 * <p>合法迁移表与硬校验由 T14（OrderStateMachine）落地。</p>
 */
public enum OrderStatus {

    PENDING_PAYMENT,
    PAID,
    PREPARING,
    COMPLETED,
    CLOSED,
    VOIDED
}
