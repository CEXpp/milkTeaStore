package com.milktea.order.ai.stream;

import com.milktea.order.ai.vo.AiDraftVo;

import java.util.List;

/**
 * AI 流式对话的 SSE 事件契约（前后端唯一真相）。
 *
 * <p>推送顺序为：{@code stage} → {@code delta}* → {@code decision}? → {@code card}? → {@code done}，
 * 任一环节失败则以 {@code error} 收尾并主动 complete。其中带 {@code ?} 的两种事件
 * 只在命中决策时出现（未命中会转既有 GLM 链路，其回复同样以 {@code delta} 下发）。</p>
 *
 * <p><b>为什么把事件类型做成常量而不是枚举</b>：{@code event:} 行里写的就是这些字符串，
 * 前端的联合类型直接照抄同一份字面量。枚举多一层 {@code name()} 转换，联调时反而多一个
 * 「枚举名与字面量不一致」的出错点。</p>
 *
 * @param event   事件名（即 SSE 的 {@code event:} 行）
 * @param payload 事件载荷（各种形态见下方记录类型）
 */
public record AiStreamEvent(String event, Object payload) {

    /** 阶段提示：替换原先的「小茶正在想」，逐段点亮。 */
    public static final String EVENT_STAGE = "stage";

    /** 文本增量：助手气泡逐字追加。 */
    public static final String EVENT_DELTA = "delta";

    /**
     * 决策结论：<b>两条分支都会下发</b>。
     *
     * <p>命中决策层时 {@code source=JEV} 并带置信度与商品编码；走既有 GLM 链路时
     * {@code source=GLM_FALLBACK}、置信度为 0、商品编码为 null。之所以失败分支也发，
     * 是为了让「这一轮到底走了哪条路」在前端与日志里都可观测——
     * 否则「最近澄清变多了」与「最近决策层挂了」会被混成同一件事。</p>
     */
    public static final String EVENT_DECISION = "decision";

    /** 草稿卡：含计价引擎现算的条目与合计。 */
    public static final String EVENT_CARD = "card";

    /** 收尾：会话标识与本次回复形态。 */
    public static final String EVENT_DONE = "done";

    /** 失败：携带业务错误码与兜底话术，前端复用既有错误处理。 */
    public static final String EVENT_ERROR = "error";

    /** 阶段一：理解需求。 */
    public static final String STAGE_UNDERSTAND = "UNDERSTAND";

    /** 阶段二：挑选最合适的一杯。 */
    public static final String STAGE_PICK = "PICK";

    /** 阶段三：算价并草稿。 */
    public static final String STAGE_PRICE = "PRICE";

    /** 决策来源：Jev 决策层。 */
    public static final String SOURCE_JEV = "JEV";

    /** 决策来源：既有 GLM 对话链路（含降级）。 */
    public static final String SOURCE_GLM = "GLM_FALLBACK";

    public static AiStreamEvent stage(String stage) {
        return new AiStreamEvent(EVENT_STAGE, new StagePayload(stage));
    }

    public static AiStreamEvent delta(String text) {
        return new AiStreamEvent(EVENT_DELTA, new DeltaPayload(text));
    }

    public static AiStreamEvent decision(String source, double confidence, String productCode) {
        return new AiStreamEvent(EVENT_DECISION, new DecisionPayload(source, confidence, productCode));
    }

    public static AiStreamEvent card(List<AiDraftVo.Item> items, String totalAmount) {
        return new AiStreamEvent(EVENT_CARD, new CardPayload(items, totalAmount));
    }

    public static AiStreamEvent done(String sessionId, String replyType) {
        return new AiStreamEvent(EVENT_DONE, new DonePayload(sessionId, replyType));
    }

    public static AiStreamEvent error(int code, String message, String fallbackText) {
        return new AiStreamEvent(EVENT_ERROR, new ErrorPayload(code, message, fallbackText));
    }

    /**
     * 阶段载荷。
     *
     * @param stage 取值见 {@link #STAGE_UNDERSTAND} / {@link #STAGE_PICK} / {@link #STAGE_PRICE}
     */
    public record StagePayload(String stage) {
    }

    /**
     * 文本增量载荷。
     *
     * @param text 本段增量文本（非累积全文；前端自行追加）
     */
    public record DeltaPayload(String text) {
    }

    /**
     * 决策载荷。
     *
     * @param source      取值见 {@link #SOURCE_JEV} / {@link #SOURCE_GLM}
     * @param confidence  聚合置信度（0..1）；走 GLM 链路时为 0
     * @param productCode 商品编码（如 {@code P03}）；走 GLM 链路时为 null
     */
    public record DecisionPayload(String source, double confidence, String productCode) {
    }

    /**
     * 草稿卡载荷（结构与既有非流式响应里的 {@code data.draft} 完全一致，
     * 前端可以复用同一套渲染组件）。
     *
     * @param items       草稿条目
     * @param totalAmount 合计金额（两位小数字符串）
     */
    public record CardPayload(List<AiDraftVo.Item> items, String totalAmount) {
    }

    /**
     * 收尾载荷。
     *
     * @param sessionId 会话标识，前端需回传以延续上下文
     * @param replyType TEXT / CARD
     */
    public record DonePayload(String sessionId, String replyType) {
    }

    /**
     * 失败载荷（字段与非流式降级形态对齐，便于前端同一处处理）。
     *
     * @param code         业务错误码（403 / 1008 / 1009 …）
     * @param message      错误描述
     * @param fallbackText 给顾客看的兜底话术；无则为 null
     */
    public record ErrorPayload(int code, String message, String fallbackText) {
    }
}
