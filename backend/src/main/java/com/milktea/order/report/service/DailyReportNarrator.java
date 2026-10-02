package com.milktea.order.report.service;

import com.milktea.order.report.prompt.DailyReportPrompt;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 日报表述层智能体（T68，W08）。
 *
 * <p><b>刻意不给任何工具</b>：指标与异常都已算完，模型只负责把结论讲成口语。
 * 给它工具就等于允许它自己去查数、自由找规律——那正是「编造趋势」的来源
 * （任务卡关键设计「AI 只做表述层，不做计算层」）。</p>
 *
 * <p><b>刻意不给记忆</b>（无 {@code @MemoryId}）：本接口是纯函数式的——每次调用都拿
 * 当天已算好的事实请模型讲一遍，输出只由入参决定。若挂上记忆窗口，昨天日报的措辞
 * 会渗进今天那篇，反而让「AI 可能编造趋势」变成真的。</p>
 */
public interface DailyReportNarrator {

    /**
     * 把算好的指标与异常讲成人话。
     *
     * @param facts 已算好的事实文本（唯一输入，不含原始表）
     * @return 口语化日报
     */
    @SystemMessage(DailyReportPrompt.SYSTEM_MESSAGE)
    String narrate(@UserMessage String facts);
}