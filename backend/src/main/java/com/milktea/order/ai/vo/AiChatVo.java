package com.milktea.order.ai.vo;

/**
 * AI 对话响应 data（LLD 3.4）。
 *
 * <p>{@code replyType} 区分两种交付形态：{@code TEXT}（纯文本，如规格追问）与
 * {@code CARD}（含草稿单卡片）。本任务（T29）无工具集，只会产出 {@code TEXT}。</p>
 *
 * @param sessionId 会话标识，前端需回传以延续上下文
 * @param replyType TEXT / CARD
 * @param text      模型回复文本
 * @param draft     草稿单；TEXT 形态为 {@code null}
 */
public record AiChatVo(
        String sessionId,
        String replyType,
        String text,
        AiDraftVo draft
) {

    /** 纯文本回复（规格追问、闲聊拉回等） */
    public static final String TYPE_TEXT = "TEXT";

    /** 卡片回复（含草稿单，T30/T31 产出） */
    public static final String TYPE_CARD = "CARD";

    /** 纯文本形态：无草稿单。 */
    public static AiChatVo text(String sessionId, String text) {
        return new AiChatVo(sessionId, TYPE_TEXT, text, null);
    }
}
