package com.milktea.order.ai.vo;

import java.util.List;

/**
 * 草稿单（LLD 3.4 响应 data.draft）。
 *
 * <p>本任务（T29）无工具集，草稿恒为空（{@code draft = null}、{@code replyType = "TEXT"}）；
 * 结构按 LLD 3.4 一次定义到位，T30/T31 只填值不改契约，避免小程序端联调返工。</p>
 *
 * @param items       草稿条目
 * @param totalAmount 合计金额（两位小数字符串）
 */
public record AiDraftVo(
        List<Item> items,
        String totalAmount
) {

    /**
     * 草稿条目（LLD 3.4 示例：珍珠奶茶 + [大杯,少冰,五分糖,珍珠] + 数量 + 单价 + 小计）。
     *
     * @param productName 商品名
     * @param optionNames 选项名列表
     * @param quantity    数量
     * @param unitPrice   单价（两位小数字符串）
     * @param itemAmount  小计（两位小数字符串）
     */
    public record Item(
            String productName,
            List<String> optionNames,
            Integer quantity,
            String unitPrice,
            String itemAmount
    ) {
    }
}
