package com.milktea.order.ai.decision;

import java.util.List;

/**
 * 一次决策的结果。
 *
 * <p><b>{@code hit} 与 {@code source} 是两件事，必须分开</b>：</p>
 * <ul>
 *   <li>{@code hit=false} 可能是「模型说这不是点单」「置信度不够」「商品编码对不上」——
 *       这些是<b>正常的决策结论</b>，应当转 GLM 链路继续对话；</li>
 *   <li>{@code source=GLM_FALLBACK} 表示 Jev 这条链路本身没好使（超时/熔断/未配置）——
 *       这是<b>基础设施层面的降级</b>，日志与监控要能一眼分开，否则
 *       「最近澄清变多了」与「最近 Jev 挂了」会被混成同一件事。</li>
 * </ul>
 *
 * <p>中文名（{@code productName} / {@code optionNames}）随结果一起带出：落草稿要经过
 * {@code DraftOrderTool}，而它按<b>中文名</b>在菜单里做精确匹配——这正是「商品只从在售集合里找」
 * 这条约束的落地方式，模型侧拿不到绕过它的机会。</p>
 *
 * @param hit        是否可以直接落草稿（置信度达标 + 结论是下单 + 规格齐全）
 * @param intent     模型判定的意图（{@link #INTENT_ORDER} / {@link #INTENT_OTHER}）
 * @param productCode 商品编码（如 {@code P03}）
 * @param productName 中文商品名（落草稿用）
 * @param optionNames 中文规格名列表（落草稿用，按规格组顺序）
 * @param quantity    数量（已约束在合法区间内）
 * @param confidence  聚合置信度（取所依赖各项答案的最小值，见 {@code JevDecisionProvider}）
 * @param source      结论来源
 * @param reason      未命中或降级的原因（写日志用；不含用户原文与昵称）
 */
public record DecisionResult(
        boolean hit,
        String intent,
        String productCode,
        String productName,
        List<String> optionNames,
        int quantity,
        double confidence,
        Source source,
        String reason) {

    /** 意图：下新单。 */
    public static final String INTENT_ORDER = "ORDER";

    /** 意图：其他（改单 / 退单 / 问事 / 闲聊）——一律交既有 GLM 链路，行为与线上一致。 */
    public static final String INTENT_OTHER = "OTHER";

    /** 结论来源。 */
    public enum Source {
        /** 由 Jev 决策层给出（含「决定不下单」这类正常结论）。 */
        JEV,
        /** Jev 不可用或结论不可用，已退回既有 GLM 链路。 */
        GLM_FALLBACK,
        /** 决策层未启用（配置关闭）——与「降级」区分，属于预期内的未开启。 */
        DISABLED
    }

    /** 命中：可程序化落草稿。 */
    public static DecisionResult hit(String productCode, String productName, List<String> optionNames,
                                     int quantity, double confidence) {
        return new DecisionResult(true, INTENT_ORDER, productCode, productName,
                optionNames == null ? List.of() : List.copyOf(optionNames),
                quantity, confidence, Source.JEV, null);
    }

    /** 未命中：模型给了结论但不足以直接下单（转 GLM 链路澄清或改写）。 */
    public static DecisionResult miss(String intent, double confidence, String reason) {
        return new DecisionResult(false, intent, null, null, List.of(), 0, confidence, Source.JEV, reason);
    }

    /** 降级：Jev 链路不可用，退回既有 GLM 链路。 */
    public static DecisionResult fallback(String reason) {
        return new DecisionResult(false, null, null, null, List.of(), 0, 0.0, Source.GLM_FALLBACK, reason);
    }

    /** 未启用：配置层面关闭了决策层。 */
    public static DecisionResult disabled() {
        return new DecisionResult(false, null, null, null, List.of(), 0, 0.0, Source.DISABLED, "jev 未启用");
    }
}
