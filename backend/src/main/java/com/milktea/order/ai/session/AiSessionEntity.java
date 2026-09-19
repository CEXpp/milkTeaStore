package com.milktea.order.ai.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话（{@code ai_session} 表，LLD 2.4 / 6.5）。
 *
 * <p>一个会话承载一份草稿单与一段消息历史；{@code session_uuid} 即 LangChain4j 的
 * {@code @MemoryId}，用于把消息窗口与具体会话绑定。</p>
 *
 * <p>列定义见 {@code V1__init_schema.sql}：{@code uk_uuid(session_uuid)}、{@code idx_customer}、
 * {@code idx_expires}。</p>
 */
@Data
@TableName("ai_session")
public class AiSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话唯一标识（UUID 字符串，36 字符；列宽 VARCHAR(64)），对外暴露的 sessionId */
    private String sessionUuid;

    /** 归属顾客：会话与草稿绑定顾客身份（SRS 5.4 / LLD 6.1） */
    private Long customerId;

    /** 草稿单 JSON：{@code [{productName,optionNames[],quantity}]}；超期作废时被置空 */
    private String draftItems;

    /** 过期时间 = 最后活动时间 + ai.session-timeout-minutes */
    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
