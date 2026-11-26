package com.milktea.order.ai.decision;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Jev 决策响应体。
 *
 * <pre>
 * { "model": "jev-1.13.0",
 *   "answers": { "best_product": { "type":"choice", "choice":"P03",
 *                                  "probabilities":{...}, "confidence":0.85 },
 *                "ready":        { "type":"noul", "noul":0.91 } },
 *   "usage": { "input_tokens": 412, "output_tokens": 67 } }
 * </pre>
 *
 * <p><b>字段名刻意用 snake_case</b>：官方 JSON 就是 {@code input_tokens}，而本项目没有全局
 * 命名策略（{@code WxSessionResp} 也是原样对齐 {@code session_key} 的写法）。直接让 record
 * 组件名与 JSON 同名，比引注解更不易出错。</p>
 *
 * <p><b>为什么不校验 {@code model} 回传值</b>：回传的是实际回答的版本化 ID，别名会漂移，
 * 因此它只用于日志追踪，不参与任何判定——阈值针对版本调过时应当靠配置钉住版本，
 * 而不是靠运行时比对回传值。</p>
 *
 * @param model   实际应答的版本化模型 id（日志用）
 * @param answers 具名答案映射，key 与请求 questions 一一对应
 * @param usage   用量（计费依据：官方按 input tokens 计费，output 免费）
 */
public record JevResponse(String model, Map<String, Answer> answers, Usage usage) {

    /**
     * 单个答案。
     *
     * @param type         回显题型
     * @param choice       choice 题型的选中 key
     * @param score        score 题型的命中等级下标（官方示例为浮点）
     * @param legend       score 题型的下标到文案映射
     * @param probabilities 概率分布（key 为选项 key 或等级下标的字符串形式）
     * @param confidence   校准置信度；<b>noul 题型没有此字段</b>
     * @param noul         noul 题型的概率值，无 confidence
     */
    public record Answer(String type,
                         String choice,
                         Double score,
                         Map<String, String> legend,
                         Map<String, Double> probabilities,
                         Double confidence,
                         Double noul) {

        /** 命中某个 choice key。 */
        public boolean chose(String key) {
            return key != null && key.equals(choice);
        }
    }

    /**
     * 用量。
     *
     * @param input_tokens  输入 token 数（计费依据）
     * @param output_tokens 输出 token 数（官方免费，仅作观测）
     */
    public record Usage(Integer input_tokens, Integer output_tokens) {

        public int inputTokensOrZero() {
            return input_tokens == null ? 0 : input_tokens;
        }

        public int outputTokensOrZero() {
            return output_tokens == null ? 0 : output_tokens;
        }
    }

    /** 取具名答案；不存在返回 {@code null}（调用方须按「答案缺失即不许落草稿」处理）。 */
    public Answer answer(String name) {
        return answers == null ? null : answers.get(name);
    }

    /** 实际作答的问题名集合（缺失检测用）。 */
    public Set<String> answeredNames() {
        return answers == null ? Collections.emptySet() : answers.keySet();
    }

    /** 输入 token 数（日志与成本观测）。 */
    public int inputTokens() {
        return usage == null ? 0 : usage.inputTokensOrZero();
    }

    /** 输出 token 数（日志与成本观测）。 */
    public int outputTokens() {
        return usage == null ? 0 : usage.outputTokensOrZero();
    }
}
