package com.milktea.order.order.vo;

import java.util.List;

/**
 * 下单响应中的订单项（LLD 3.3）：金额一律为两位小数字符串。
 *
 * @param productName 商品名快照
 * @param quantity    数量
 * @param unitPrice   快照单价（两位小数字符串）
 * @param optionNames 规格项名称列表（按规格组 / 规格项排序）
 * @param itemAmount  单项金额（两位小数字符串）
 */
public record OrderItemVo(
        String productName,
        Integer quantity,
        String unitPrice,
        List<String> optionNames,
        String itemAmount
) {
}
