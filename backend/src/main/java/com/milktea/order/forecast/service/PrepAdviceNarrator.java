package com.milktea.order.forecast.service;

import com.milktea.order.forecast.prompt.PrepAdvicePrompt;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 备料建议表述层智能体（T69，W09）。
 *
 * <p><b>不注册任何工具</b>：预测单量与销量结构都已算完，模型只负责措辞。
 * 给它工具就等于允许它自己去查数、自由找规律——那正是编造的依据。
 * 与 T68 日报表述层同一思路。</p>
 *
 * <p><b>刻意不给记忆</b>（无 {@code @MemoryId}，同 T68）：每次预测都是一次性的事实→措辞，
 * 挂记忆窗口只会让上一次的措辞渗进这一次。</p>
 */
public interface PrepAdviceNarrator {

    /**
     * 把算好的预测讲成一句备料建议。
     *
     * @param facts 已算好的事实文本
     * @return 备料建议
     */
    @SystemMessage(PrepAdvicePrompt.SYSTEM_MESSAGE)
    String advise(@UserMessage String facts);
}