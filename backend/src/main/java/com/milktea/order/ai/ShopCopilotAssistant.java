package com.milktea.order.ai;

import com.milktea.order.ai.prompt.ShopCopilotPrompt;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 店长 Copilot 智能体接口（T66/T67，W07）：由 {@code AiServices} 声明式装配。
 *
 * <p><b>与 {@link OrderAssistant} 分开装配、互不共享工具集</b>：点单员有改草稿的工具，
 * 经营参谋只有统计查询工具。两个 {@code AiServices} 实例各自持有工具集，
 * 因此「顾客侧的点单助手能改数据」不会泄漏到「店长侧的参谋能改数据」上。</p>
 *
 * @see com.milktea.order.ai.tools.ShopStatsTool
 */
public interface ShopCopilotAssistant {

    /**
     * 一轮经营问答。
     *
     * @param conversationId 会话标识，用于隔离多轮上下文（同一店长的多轮追问）
     * @param message        店长的自然语言提问
     * @return 模型应答文本
     */
    @SystemMessage(ShopCopilotPrompt.SYSTEM_MESSAGE)
    String chat(@MemoryId String conversationId, @UserMessage String message);
}