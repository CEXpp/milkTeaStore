package com.milktea.order.order.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 订单详情（LLD 3.3 {@code GET /api/customer/orders/{id}}）：含完整 items 快照明细。
 *
 * <p>所有金额为两位小数字符串、时间为 yyyy-MM-dd HH:mm:ss；商品名 / 单价 / 规格名均为
 * 下单瞬间的快照值，商品后续改价或规格改名不影响本响应（LLD 2.4）。</p>
 */
@Data
public class OrderDetailVo implements Serializable {

    private Long id;

    /** 订单号：yyMMdd + 5 位序列（T11 发放）。 */
    private String orderNo;

    /** 来源渠道：MINI_PROGRAM / AI / COUNTER。 */
    private String source;

    private String status;

    private String pickupCode;

    /** 应付总额（两位小数字符串）。 */
    private String totalAmount;

    /** 口味备注。 */
    private String remark;

    private String createdAt;

    private String paidAt;

    private String startedAt;

    private String completedAt;

    private String closedAt;

    private String voidedAt;

    /** 作废原因（商家填写，未作废为 null）。 */
    private String voidReason;

    /** 订单项明细（含规格快照）。 */
    private List<OrderDetailItemVo> items;
}
