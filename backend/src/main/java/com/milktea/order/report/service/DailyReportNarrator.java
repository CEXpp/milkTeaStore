package com.milktea.order.report.service;

import com.milktea.order.report.prompt.DailyReportPrompt;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 日报表述层智能体（T68，W08）。
 *
 * <p><b>刻意不给任何工具</b>：指标与异常都已算完，模型只负责把结论讲成口语。
 * 给它工具就等于允许它自己去查数、自由找规律——那正是「编造趋势」的来源
 * （任务卡关键设计「AI 只做表述层，不做计算层」）。</p>
 *
 * <p>本接口无多轮上下文，{@code sessionId} 传固定值即可（保留参数是为了与项目内
 * 其他 {@code AiServices} 的装配方式一致）。</p>
 */
public interface DailyReportNarrator {

    /**
     * 把算好的指标与异常讲成人话。
     *
     * @param sessionId 占位会话标识
     * @param facts     已算好的事实文本（唯一输入，不含原始表）
     * @return 口语化日报
     */
    @SystemMessage(DailyReportPrompt.SYSTEM_MESSAGE)
    String narrate(@MemoryId String sessionId, @UserMessage String facts);
}