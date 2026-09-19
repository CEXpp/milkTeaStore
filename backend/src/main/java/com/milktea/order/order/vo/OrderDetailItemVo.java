package com.milktea.order.order.vo;

import com.milktea.order.order.dto.OptionSnapshot;

import java.util.List;

/**
 * 订单详情中的订单项（含规格快照明细，LLD 3.3 详情接口）。
 *
 * @param productId  关联商品 id（统计排行用）
 * @param productName 商品名快照
 * @param quantity    数量
 * @param basePrice   基础价快照（两位小数字符串）
 * @param unitPrice   快照单价 = 基础价 + Σ价差（两位小数字符串）
 * @param options     规格快照（组名 / 项名 / 价差，按快照顺序）
 * @param itemAmount  单项金额（两位小数字符串）
 */
public record OrderDetailItemVo(
        Long productId,
        String productName,
        Integer quantity,
        String basePrice,
        String unitPrice,
        List<OptionSnapshot> options,
        String itemAmount
) {
}
