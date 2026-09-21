package com.milktea.order.ai.draft;

import java.util.List;

/**
 * AI 草稿单条目（{@code ai_session.draft_items} 的 JSON 元素，LLD 6.5 口径）。
 *
 * <p><b>只含名字与数量、不含价格</b>：金额一律由计价引擎在读写时现算，模型侧不存在任何价格字段
 * （SRS 约束三原则「价格一律后端计算」）。</p>
 *
 * @param productName 商品名（写入时已规范为库中商品名）
 * @param optionNames 选项名列表（按规格组 sort_order、组内 sort_order 展开）
 * @param quantity    数量
 */
public record DraftItem(
        String productName,
        List<String> optionNames,
        Integer quantity
) {
}
