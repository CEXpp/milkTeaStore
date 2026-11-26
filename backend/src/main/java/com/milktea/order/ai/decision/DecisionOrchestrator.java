package com.milktea.order.ai.decision;

import com.milktea.order.ai.draft.DraftItem;
import com.milktea.order.ai.session.SessionService;
import com.milktea.order.ai.tools.DraftOrderTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 决策编排：组装上下文 → 请求裁决 → 按置信度路由 → 程序化落草稿。
 *
 * <h2>职责边界</h2>
 * <p>本类只回答一个问题：<b>「这一轮能不能直接定下来一杯奶茶，并且已经写进草稿了？」</b>
 * 返回 {@code hit=true} 才表示草稿真的落好了；其余一切情况（意图不是下单、置信度不足、
 * 模型给了菜单外编码、Jev 挂了、草稿写失败）都返回 {@code hit=false}，
 * 由调用方转入既有 GLM 链路——<b>对顾客的表现与升级前逐字相同</b>。</p>
 *
 * <h2>草稿写入为什么不自己写</h2>
 * <p>直接复用 {@link DraftOrderTool#updateDraftOrder}，而不是自己调计价引擎 + 会话服务。
 * 那个方法里装着「AI 约束三原则」的全部落地：商品名只在在售集合里找、规格名逐个匹配启用组、
 * 单选组漏选拒绝、金额一律走计价引擎。自己再写一遍，就等于把同一套安全边界复制成两份，
 * 早晚会漂移——而漂移的方向一定是「有一份忘了校验」。</p>
 *
 * <p><b>并且不相信它的返回值文本</b>：{@code DraftOrderTool} 用中文文案表达失败
 * （「该商品需要选择杯型。」），按文案判断成败是脆弱且不可测的。这里改为
 * <b>回读草稿</b>：目标条目确实出现在会话草稿里，才算落成。</p>
 *
 * <h2>日志纪律</h2>
 * <p>只记编码、置信度与原因，不记顾客原话与昵称（{@code MenuState} 的 requestBlock 从不进日志）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionOrchestrator {

    private final JevProperties properties;
    private final DecisionProvider decisionProvider;
    private final MenuStateAssembler menuStateAssembler;
    private final SessionService sessionService;
    private final DraftOrderTool draftOrderTool;

    /**
     * 做一次决策，命中即把结果写进会话草稿。
     *
     * @param sessionUuid 会话标识（草稿挂在会话上）
     * @param userMessage 顾客原话
     * @param preference  该顾客的偏好快照（{@link PreferenceSnapshot#empty()} 表示无历史）
     * @return 决策结果；{@code hit=true} 表示草稿已落好
     */
    public DecisionResult decide(String sessionUuid, String userMessage, PreferenceSnapshot preference) {
        if (!properties.configured()) {
            // 与「降级」区分：这是配置层面没开，属于预期内状态，不该计入故障
            return DecisionResult.disabled();
        }

        MenuState state;
        try {
            state = menuStateAssembler.assemble(userMessage, preference, sessionService.loadDraft(sessionUuid));
        } catch (Exception e) {
            log.warn("[JEV] 组装决策上下文失败，降级既有 GLM 链路：{}", e.getMessage());
            return DecisionResult.fallback("组装决策上下文失败");
        }

        DecisionResult decision;
        try {
            decision = decisionProvider.decide(state);
        } catch (JevUnavailableException e) {
            // 官方错误表下的所有失败都汇聚到这里：认证/校验/限流/过载/超时/熔断/解析
            log.warn("[JEV] 决策链路不可用，降级既有 GLM 链路：{}", e.getMessage());
            return DecisionResult.fallback(e.getMessage());
        } catch (Exception e) {
            log.warn("[JEV] 决策出现未预期异常，降级既有 GLM 链路", e);
            return DecisionResult.fallback("决策未预期异常：" + e.getClass().getSimpleName());
        }

        if (!decision.hit()) {
            log.info("[JEV] 不直接下单，转 GLM 链路：intent={} confidence={} reason={}",
                    decision.intent(), decision.confidence(), decision.reason());
            return decision;
        }

        if (!applyToDraft(sessionUuid, decision)) {
            // 模型说可以下单，但落草稿被约束层拒绝（如规格越界）——以约束层为准
            log.warn("[JEV] 决策命中但草稿未落成，转 GLM 链路确认：productCode={} reason={}",
                    decision.productCode(), decision.reason());
            return DecisionResult.miss(DecisionResult.INTENT_ORDER, decision.confidence(),
                    "草稿未落成，已转 GLM 链路确认");
        }
        log.info("[JEV] 已按决策落草稿：productCode={} quantity={} confidence={}",
                decision.productCode(), decision.quantity(), decision.confidence());
        return decision;
    }

    /**
     * 程序化落草稿并回读验证。
     *
     * @return 目标条目确实出现在草稿里
     */
    private boolean applyToDraft(String sessionUuid, DecisionResult decision) {
        try {
            draftOrderTool.updateDraftOrder(decision.productName(), decision.optionNames(),
                    decision.quantity(), sessionUuid);
        } catch (Exception e) {
            log.warn("[JEV] 草稿写入异常：productCode={} reason={}", decision.productCode(), e.getMessage());
            return false;
        }
        for (DraftItem item : sessionService.loadDraft(sessionUuid)) {
            // 数量也要比对：草稿是「同名同规格即覆盖数量」的语义，写错数量同样是错的
            if (decision.productName().equals(item.productName())
                    && sameOptions(item.optionNames(), decision.optionNames())
                    && item.quantity() != null && item.quantity() == decision.quantity()) {
                return true;
            }
        }
        return false;
    }

    /** 规格集合比较：忽略顺序（两个来源的展开顺序可能不同，但集合相同即同一杯）。 */
    private boolean sameOptions(List<String> left, List<String> right) {
        List<String> a = sorted(left);
        List<String> b = sorted(right);
        return a.equals(b);
    }

    private List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values == null ? List.of() : values);
        copy.sort(String::compareTo);
        return copy;
    }
}
