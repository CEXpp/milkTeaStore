package com.milktea.order.order.vo;

import java.io.Serializable;

/**
 * 柜台人工点单响应（T14/T20，LLD 3.5 {@code POST /api/admin/counter-orders}）：
 * {@code {orderId, orderNo, pickupCode, totalAmount}}——收银员确认收款后弹大号取餐码窗口。
 *
 * @param orderId    订单主键（供核对）
 * @param orderNo    订单号（yyMMdd + 5 位序列）
 * @param pickupCode 取餐码（与小程序单共用当日流水，创建即分配）
 * @param totalAmount 实付金额（两位小数字符串）
 */
public record CounterOrderVo(Long orderId, String orderNo, String pickupCode, String totalAmount)
        implements Serializable {
}
