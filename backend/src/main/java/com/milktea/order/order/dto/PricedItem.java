package com.milktea.order.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单项计价结果：{@code unitPrice = basePrice + Σ priceDelta}，{@code itemAmount = unitPrice × quantity}。
 */
@Data
public class PricedItem {

    private Long productId;

    /** 商品名快照 */
    private String productName;

    /** 基础价快照 */
    private BigDecimal basePrice;

    /** 规格快照，按规格组 sort_order、规格项 sort_order 升序 */
    private List<OptionSnapshot> options;

    private Integer quantity;

    /** 单杯价 = 基础价 + 杯型价差 + Σ加料价差 */
    private BigDecimal unitPrice;

    /** 单项金额 = 单杯价 × 数量 */
    private BigDecimal itemAmount;
}
