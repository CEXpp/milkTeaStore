package com.milktea.order.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI 对话请求（LLD 3.4）。
 *
 * <p>{@code sessionId} 首轮可为空（由后端新建会话并通过响应返回）；{@code message} 为顾客输入。</p>
 */
@Data
public class AiChatRequest {

    /** 会话标识；为空表示首轮对话，后端新建会话 */
    private String sessionId;

    /** 顾客输入（输入法语音转文字或手打，SRS 5.1） */
    @NotBlank(message = "消息不能为空")
    private String message;
}
