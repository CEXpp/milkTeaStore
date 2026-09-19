package com.milktea.order.ai.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话消息（{@code ai_message} 表，LLD 6.5）。
 *
 * <p>一行一条 {@code ChatMessage}：{@link #role} 是会话角色，{@link #content} 是该消息的
 * LangChain4j JSON 序列化文本（由 {@code ChatMessageSerializer} / {@code ChatMessageDeserializer} 往返）。
 * 单列承载 USER / ASSISTANT / TOOL 三角色，无需为工具调用另建列。</p>
 *
 * <p>列定义见 {@code V1__init_schema.sql}：{@code idx_session(session_id, id)}。</p>
 */
@Data
@TableName("ai_message")
public class AiMessageEntity {

    /** 会话角色：{@link #ROLE_USER} / {@link #ROLE_ASSISTANT} / {@link #ROLE_TOOL} / {@link #ROLE_SYSTEM} */
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String ROLE_TOOL = "TOOL";
    /** 系统提示词消息（{@code @SystemMessage} 会被 AiServices 写入记忆窗口） */
    public static final String ROLE_SYSTEM = "SYSTEM";
    /** 其他自定义消息类型的兜底角色 */
    public static final String ROLE_CUSTOM = "CUSTOM";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 {@code ai_session.id} */
    private Long sessionId;

    /** USER / ASSISTANT / TOOL / SYSTEM / CUSTOM */
    private String role;

    /** LangChain4j ChatMessage 的 JSON 文本 */
    private String content;

    private LocalDateTime createdAt;
}
