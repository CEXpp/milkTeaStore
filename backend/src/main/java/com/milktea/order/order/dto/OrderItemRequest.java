package com.milktea.order.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 下单项请求：购物车 / 柜台单 / AI 草稿三个入口共用的计价输入。
 *
 * <p>只描述「要什么」，不含任何价格字段——价格一律由后端
 * {@code PricingService#calculatePrice} 计算（SRS 约束三原则「价格一律后端计算」）。
 * 数量上限对齐 LLD 3.2：超限报 1001 参数错误。</p>
 */
@Data
public class OrderItemRequest {

    /** 单品数量下限 */
    public static final int MIN_QUANTITY = 1;

    /** 单品数量上限 */
    public static final int MAX_QUANTITY = 20;

    @NotNull(message = "productId 不能为空")
    private Long productId;

    /**
     * 选中的规格项 id 列表。
     * 单选组（如杯型 / 温度 / 甜度）必须恰选 1 项；多选组（如加料）0..n 项。
     */
    private List<Long> optionIds = new ArrayList<>();

    @NotNull(message = "quantity 不能为空")
    @Min(value = MIN_QUANTITY, message = "quantity 不能小于 1")
    @Max(value = MAX_QUANTITY, message = "quantity 不能大于 20")
    private Integer quantity;

    public OrderItemRequest() {
    }

    public OrderItemRequest(Long productId, List<Long> optionIds, Integer quantity) {
        this.productId = productId;
        this.optionIds = optionIds == null ? new ArrayList<>() : new ArrayList<>(optionIds);
        this.quantity = quantity;
    }
}
