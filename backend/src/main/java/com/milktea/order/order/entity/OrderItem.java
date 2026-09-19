package com.milktea.order.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单项（order_item 表）：商品名 / 基础价 / 单价 / 规格快照均为下单瞬间的锁定值，
 * 后续商品改价或规格改名不影响历史订单（LLD 2.4 快照规则）。
 */
@Data
@TableName("order_item")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    /** 关联商品（统计排行用） */
    private Long productId;

    /** 商品名快照 */
    private String productName;

    /** 基础价快照 */
    private BigDecimal basePrice;

    /**
     * 规格快照 JSON：[{groupId,groupName,optionId,optionName,priceDelta}]，
     * 由 PricingService 产出的 {@code List<OptionSnapshot>} 序列化而来。
     */
    private String optionsSnapshot;

    private Integer quantity;

    /** 快照单价 = 基础价 + Σ 价差 */
    private BigDecimal unitPrice;

    /** 单项金额 = 单价 × 数量 */
    private BigDecimal itemAmount;
}
