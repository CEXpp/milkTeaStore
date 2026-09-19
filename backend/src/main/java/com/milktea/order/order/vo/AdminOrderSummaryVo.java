package com.milktea.order.order.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 商家三动作（start / complete / void）响应 data：更新后的订单摘要（T14，LLD 3.5）。
 *
 * <p>字段与看板卡片结构对齐，前端操作成功后可直接以本响应刷新对应卡片
 * （3 秒轮询到达前无需等待）。</p>
 */
@Data
public class AdminOrderSummaryVo implements Serializable {

    private Long orderId;

    private String orderNo;

    private String status;

    private String pickupCode;

    private String source;

    /** 商品摘要："商品名x数量(规格/规格)"（与顾客端列表同构）。 */
    private List<String> items;

    private String totalAmount;

    private String paidAt;

    private String startedAt;

    private String completedAt;

    private String voidedAt;

    private String voidReason;
}
