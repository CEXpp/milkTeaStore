package com.milktea.order.ai;

import com.milktea.order.ai.prompt.SystemPrompt;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * AI 点单员智能体接口（LLD 6.1）：由 {@code AiServices} 声明式装配。
 *
 * <p>装配要点（见 {@link com.milktea.order.ai.config.LangChain4jConfig}）：
 * 系统提示词 + 会话记忆（{@code MessageWindowChatMemory} 映射 {@code ai_message} 表）。
 * {@code @MemoryId} 绑定 {@code session_uuid}，实现按会话隔离的多轮上下文。</p>
 *
 * <p><b>工具集不在本任务范围</b>：LLD 6.3 的四个 {@code @Tool} 由 T30 以
 * {@code AiServices.tools(...)} 追加注册，本接口签名保持不变。</p>
 */
public interface OrderAssistant {

    /**
     * 一轮对话：模型应答文本。工具注册后模型会自动多轮调用工具（LLD 6.6 步骤 2）。
     *
     * @param sessionId 会话标识（{@code ai_session.session_uuid}），用于定位独立的记忆窗口
     * @param message   顾客输入
     * @return 模型回复文本
     */
    @SystemMessage(SystemPrompt.SYSTEM_MESSAGE)
    String chat(@MemoryId String sessionId, @UserMessage String message);
}
