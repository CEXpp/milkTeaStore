package com.milktea.order.order.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 订单列表项（LLD 3.3 {@code GET /api/customer/orders/active} 与 {@code GET /api/customer/orders} 共用）：
 *
 * <pre>
 * { "id": 3001, "status": "PREPARING", "pickupCode": "018",
 *   "totalAmount": "55.00", "createdAt": "2026-09-12 14:21:55",
 *   "items": [ "珍珠奶茶x1(大杯/少冰/珍珠)" ] }
 * </pre>
 *
 * <p>items 为「商品名x数量(规格名/规格名)」摘要串，规格按快照顺序拼接；无规格时省略括号段。
 * 历史分页列表在此结构上另填终态时间字段（未发生的事件保持 null）。</p>
 */
@Data
public class OrderListItemVo implements Serializable {

    private Long id;

    /** 订单状态：见 {@link com.milktea.order.order.entity.OrderStatus}。 */
    private String status;

    /** 取餐码：PAID 及以后状态才有值，否则为 null。 */
    private String pickupCode;

    /** 应付总额（两位小数字符串）。 */
    private String totalAmount;

    /** 下单时间，契约格式 yyyy-MM-dd HH:mm:ss。 */
    private String createdAt;

    /** 商品摘要列表，如 "珍珠奶茶x1(大杯/少冰/珍珠)"。 */
    private List<String> items;

    /** 支付时间（终态时间字段，未支付为 null）。 */
    private String paidAt;

    /** 出餐时间（终态时间字段，未出餐为 null）。 */
    private String completedAt;

    /** 超时关闭时间（终态时间字段，未关闭为 null）。 */
    private String closedAt;

    /** 作废时间（终态时间字段，未作废为 null）。 */
    private String voidedAt;
}
