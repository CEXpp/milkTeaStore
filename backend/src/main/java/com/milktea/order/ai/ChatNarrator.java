package com.milktea.order.ai;

import com.milktea.order.ai.prompt.SystemPrompt;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 流式旁白层（并行流式的「分支 A」，由 {@code AiServices} 声明式装配）。
 *
 * <p><b>刻意不挂 {@code @MemoryId}、不挂任何 {@code @Tool}</b>，理由有两条：</p>
 * <ol>
 *   <li><b>不需要记忆</b>：旁白只输出一句承接话术，与历史无关。给它记忆只会让它
 *       把上一轮的上下文带进来（从而有可能「提前替决策说话」）。
 *       这与既有 {@code DailyReportNarrator} / {@code PrepAdviceNarrator} 的处理一致
 *       ——旁白层一律不带记忆。</li>
 *   <li><b>装配期校验</b>：项目里的 {@code StartupWiringTest} 断言「带 {@code @MemoryId}
 *       的 AiService 接口恰好 2 个」（点单助手与店长 Copilot），且每个都必须配
 *       {@code chatMemoryProvider}。本接口若加上 {@code @MemoryId} 而忘了配 provider，
 *       应用会直接起不来；两者同时加上又会打破那条断言。干脆不要记忆，语义上也更干净。</li>
 * </ol>
 *
 * <p><b>不挂工具</b>：写草稿是决策层与工具集的事。旁白若手里有工具，就可能自己
 * 去改草稿，与并行跑的决策分支互相打架——「只读的旁白」是并发正确性的前提。</p>
 */
public interface ChatNarrator {

    /**
     * 生成一句承接话术（流式）。
     *
     * @param userMessage 顾客原话（仅用于让话术贴合语境，不产出实质结论）
     * @return 增量 token 流，由 {@link AiNarrator} 消费
     */
    @SystemMessage(SystemPrompt.NARRATOR_MESSAGE)
    TokenStream narrate(@UserMessage String userMessage);
}
