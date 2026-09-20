package com.milktea.order.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milktea.order.ai.session.AiMessageEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * AI 会话消息 Mapper（{@code ai_message} 表）。
 */
public interface AiMessageMapper extends BaseMapper<AiMessageEntity> {

    /**
     * 按会话清空消息。用于记忆窗口「整窗覆盖」写回（{@code ChatMemoryStoreImpl#updateMessages}）
     * 与会话记忆删除（{@code ChatMemoryStoreImpl#deleteMessages}）。
     *
     * @param sessionId {@code ai_session.id}
     * @return 删除行数
     */
    @Delete("DELETE FROM ai_message WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") Long sessionId);
}
