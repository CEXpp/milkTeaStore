package com.milktea.order.ai.decision;

import java.util.List;
import java.util.Map;

/**
 * Jev 决策请求体（官方契约只有三个字段）。
 *
 * <pre>
 * POST /v1/systemone
 * { "model": "jev-1.13.0", "state": "...", "questions": { "&lt;name&gt;": {...} } }
 * </pre>
 *
 * <p><b>state 用 String 而非 object</b>：官方允许 string / object / array，但 object 需按其
 * 内部规则序列化。自己拼成一段有层级的英文文本，行为完全可控，出问题也能一眼看出是哪一段。</p>
 *
 * <p><b>三类问题</b>（官方契约）：</p>
 * <ul>
 *   <li>{@code choice}：{@code criteria} 是 {@code {key: 说明}} 映射，<b>上限 255 项</b>；</li>
 *   <li>{@code score}：{@code criteria} 是<b>有序等级数组，2–10 级</b>；</li>
 *   <li>{@code noul}：是否判断，{@code criteria} 可选 {@code {true, false}}，且<b>响应没有 confidence</b>。</li>
 * </ul>
 *
 * <p>注意 {@code noul} 无 confidence 这一点决定了路由设计：置信度只能从 choice / score 取，
 * 不能用 noul 兜底。</p>
 *
 * @param model     模型或版本号
 * @param state     上下文（全量菜单 + 规格 + 偏好 + 顾客原话）
 * @param questions 具名问题映射；答案的 key 与之一一对应
 */
public record JevRequest(String model, String state, Map<String, Question> questions) {

    /**
     * 单个问题。
     *
     * @param type         取值见 {@link Question#TYPE_CHOICE} 等
     * @param instructions 英文问题描述（官方声明英文准确率最佳）
     * @param criteria     占位类型随题型变化：choice 为 Map，score 为 List，noul 为 Map
     */
    public record Question(String type, String instructions, Object criteria) {

        public static final String TYPE_CHOICE = "choice";
        public static final String TYPE_SCORE = "score";
        public static final String TYPE_NOUL = "noul";

        /**
         * 单选裁决题：在候选里挑一个。
         *
         * @param instructions 英文题目
         * @param options      候选映射（key 为稳定编码，value 为英文说明）
         */
        public static Question choice(String instructions, Map<String, String> options) {
            return new Question(TYPE_CHOICE, instructions, options);
        }

        /**
         * 打分题：2–10 级有序等级，用于「多选」语义（每个候选项各出一题、两级）。
         *
         * @param instructions 英文题目
         * @param levels       有序等级，长度须在 2..10
         */
        public static Question score(String instructions, List<String> levels) {
            return new Question(TYPE_SCORE, instructions, levels);
        }

        /**
         * 是否判断题。
         *
         * <p>显式给出 {@code {true, false}} 而非省略 criteria：两者都符合官方契约，
         * 但显式给标签能让模型理解「true 具体意味着什么」，对校准有正面作用。</p>
         */
        public static Question yesNo(String instructions, String trueLabel, String falseLabel) {
            return new Question(TYPE_NOUL, instructions, Map.of("true", trueLabel, "false", falseLabel));
        }
    }
}
