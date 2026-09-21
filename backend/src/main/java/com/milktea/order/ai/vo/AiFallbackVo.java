package com.milktea.order.ai.vo;

/**
 * AI 降级响应 data（LLD 3.4）。
 *
 * <p>模型超时或 HTTP 错误时的响应形态：{@code code=1008}，{@code message} 为
 * {@link com.milktea.order.common.exception.ErrorCode#AI_UNAVAILABLE} 的文案，
 * {@code data.fallbackText} 给出给顾客看的兜底话术，前端展示并引导切换 Tab 手动点单。</p>
 *
 * @param fallbackText 兜底话术（前端直显）
 */
public record AiFallbackVo(String fallbackText) {

    /** 兜底话术，文案取自 LLD 3.4 原文 */
    public static final String FALLBACK_TEXT = "AI 助手休息中，请先到菜单手动点单";

    /** 模型不可用时的标准降级数据体。 */
    public static AiFallbackVo aiUnavailable() {
        return new AiFallbackVo(FALLBACK_TEXT);
    }
}
