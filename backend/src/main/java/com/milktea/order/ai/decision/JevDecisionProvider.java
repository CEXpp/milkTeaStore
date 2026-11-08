package com.milktea.order.ai.decision;

import com.milktea.order.order.dto.OrderItemRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jev 实现的决策提供者：一次请求多问，把答案解释成「一杯具体的奶茶」。
 *
 * <h2>为什么一次请求问全部问题</h2>
 * <p>官方语义是 {@code state} 只摄入一次、所有问题<b>并行评测</b>（speculative fan-out）。
 * 因此不能先问「选哪个商品」再按商品问规格——那样要发两跳。这里把全局四组规格一次性问完，
 * 拿到商品编码后再<b>只取该商品适用的组</b>，多问的那几个答案直接忽略，代价是几个 token。</p>
 *
 * <h2>答案全用 ASCII 编码，中文只在描述位</h2>
 * <p>题面与选项键是英文与下标（{@code P03} / {@code 2}），模型不需要输出任何中文；
 * 中文只出现在「选项说明」里。这样即便模型对中文的理解有偏差，错的也只是语义判断，
 * 不会变成「返回了一个我们解析不了的东西」。</p>
 *
 * <h2>置信度怎么聚合</h2>
 * <p>取<b>所依赖各项答案 confidence 的最小值</b>，而不是平均值。点单一杯奶茶是「与」的关系：
 * 商品、杯型、温度、甜度、数量任何一项拿不准，整杯就是错的。取最小值意味着
 * 「有一项心里没底就转澄清」，这与本方案「宁可多问一句，不可猜错下单」的底线一致。</p>
 *
 * <p>注意 {@code noul}（是否判断）题型<b>没有 confidence</b>，故 {@code ready_to_order}
 * 用它的概率值做门限，不参与 confidence 聚合——否则会给聚合塞一个恒为 0 的假值。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JevDecisionProvider implements DecisionProvider {

    /** 问题名：意图。 */
    static final String Q_INTENT = "intent";

    /** 问题名：信息是否足以直接下单。 */
    static final String Q_READY = "ready_to_order";

    /** 问题名：选哪个商品。 */
    static final String Q_PRODUCT = "product";

    /** 问题名：几杯。 */
    static final String Q_QUANTITY = "quantity";

    /** 单选规格组的问题名前缀。 */
    private static final String GROUP_PREFIX = "grp_";

    /** 多选规格组（加料）逐项打分的问题名前缀。 */
    private static final String OPTION_PREFIX = "opt_";

    /** 多选组打分等级：0 = 不加，1 = 加。 */
    private static final List<String> SELECT_LEVELS = List.of("not selected", "selected");

    /** score 命中所需要的等级（两级题里即「加」）。 */
    private static final double SELECTED_LEVEL = 0.5;

    /** {@code ready_to_order} 的最低概率（该题型无 confidence，故用概率做门限）。 */
    private static final double READY_MIN = 0.5;

    private final JevClient jevClient;
    private final JevProperties properties;

    @Override
    public DecisionResult decide(MenuState state) {
        if (state.productByCode() == null || state.productByCode().isEmpty()) {
            return DecisionResult.miss(null, 0.0, "在售菜单为空，无可选商品");
        }
        JevRequest request = buildRequest(state);
        // 不可用时抛出 JevUnavailableException，由 DecisionOrchestrator 统一降级
        JevResponse response = jevClient.ask(request);
        DecisionResult result = interpret(state, response);
        log.info("[JEV] 决策完成 model={} inputTokens={} outputTokens={} hit={} confidence={} productCode={} reason={}",
                response.model(), response.inputTokens(), response.outputTokens(),
                result.hit(), format(result.confidence()), result.productCode(), result.reason());
        return result;
    }

    /**
     * 组装问题集：意图 / 是否可下单 / 商品 / 数量 + 每组规格。
     *
     * <p>问题名尽量保持 ASCII，与题型一一对应便于在响应里反查。</p>
     */
    private JevRequest buildRequest(MenuState state) {
        Map<String, JevRequest.Question> questions = new LinkedHashMap<>();

        questions.put(Q_INTENT, JevRequest.Question.choice(
                "Does the customer want to place a NEW drink order, or something else such as modifying or "
                        + "cancelling an existing order, asking about the shop, or small talk?",
                Map.of(
                        DecisionResult.INTENT_ORDER, "A new drink order: choosing one or more drinks to buy",
                        DecisionResult.INTENT_OTHER, "Anything else: modify/cancel an existing order, shop question, "
                                + "small talk, or no clear purchase intent")));

        questions.put(Q_READY, JevRequest.Question.yesNo(
                "Is the request specific enough to build exactly one cart line item for one drink right now, "
                        + "without asking the customer a follow-up question about which product or which required option to use?",
                "Yes, one well-defined drink can be added to the cart now",
                "No, a follow-up question is required first"));

        Map<String, String> productOptions = new LinkedHashMap<>();
        for (MenuState.ProductRef ref : state.productByCode().values()) {
            productOptions.put(ref.code(), ref.label() + " | base price " + ref.basePrice());
        }
        questions.put(Q_PRODUCT, JevRequest.Question.choice(
                "Which single product best matches what the customer asked for? Pick exactly one.",
                productOptions));

        Map<String, String> quantityOptions = new LinkedHashMap<>();
        for (int quantity = OrderItemRequest.MIN_QUANTITY; quantity <= OrderItemRequest.MAX_QUANTITY; quantity++) {
            quantityOptions.put(String.valueOf(quantity), quantity + (quantity == 1 ? " cup" : " cups"));
        }
        questions.put(Q_QUANTITY, JevRequest.Question.choice(
                "How many cups of the chosen product does the customer want? If the customer did not say, answer 1.",
                quantityOptions));

        for (MenuState.SpecGroupRef group : state.groupByCode().values()) {
            appendGroupQuestions(questions, group);
        }
        return new JevRequest(properties.modelOrDefault(), state.state(), questions);
    }

    /** 单个规格组的问题：多选组逐项打分，单选组出单选题。 */
    private void appendGroupQuestions(Map<String, JevRequest.Question> questions, MenuState.SpecGroupRef group) {
        if (group.multiSelect()) {
            for (MenuState.OptionRef option : group.options()) {
                questions.put(optionQuestionName(group.code(), option.index()), JevRequest.Question.score(
                        "Should the topping '" + option.label() + "' be added to this drink?"
                                + priceHint(option.priceDelta()),
                        SELECT_LEVELS));
            }
            return;
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (MenuState.OptionRef option : group.options()) {
            options.put(String.valueOf(option.index()), option.label() + priceHint(option.priceDelta()));
        }
        questions.put(groupQuestionName(group.code()), JevRequest.Question.choice(
                "Which " + group.code() + " should this drink use? Pick exactly one. "
                        + "If the customer did not say, prefer the customer's learned preference; "
                        + "otherwise pick the first option.",
                options));
    }

    /**
     * 解释答案：逐项校验，任何一项不满足就返回「不下单」。
     *
     * <p>所有拒绝路径都返回 {@code hit=false} 而非抛异常——「模型认为信息不够」是正常结论，
     * 应当交给 GLM 去追问，不是故障。</p>
     */
    private DecisionResult interpret(MenuState state, JevResponse response) {
        JevResponse.Answer intent = response.answer(Q_INTENT);
        if (intent == null || intent.choice() == null) {
            return DecisionResult.miss(null, 0.0, "缺少 intent 答案");
        }
        if (!DecisionResult.INTENT_ORDER.equals(intent.choice())) {
            return DecisionResult.miss(intent.choice(), confidenceOf(intent), "意图非下单：" + intent.choice());
        }
        double minConfidence = confidenceOf(intent);

        JevResponse.Answer ready = response.answer(Q_READY);
        if (ready == null || ready.noul() == null) {
            return DecisionResult.miss(DecisionResult.INTENT_ORDER, minConfidence, "缺少 ready_to_order 答案");
        }
        if (ready.noul() < READY_MIN) {
            return DecisionResult.miss(DecisionResult.INTENT_ORDER, minConfidence,
                    "信息不足以直接下单（ready=" + format(ready.noul()) + "）");
        }

        JevResponse.Answer productAnswer = response.answer(Q_PRODUCT);
        if (productAnswer == null || productAnswer.choice() == null) {
            return DecisionResult.miss(DecisionResult.INTENT_ORDER, minConfidence, "缺少 product 答案");
        }
        MenuState.ProductRef product = state.product(productAnswer.choice());
        if (product == null) {
            // 防幻觉编码：模型给出的编码必须在本次 state 里真实存在，否则一律不落草稿
            return DecisionResult.miss(DecisionResult.INTENT_ORDER, minConfidence,
                    "商品编码不在在售集合：" + productAnswer.choice());
        }
        minConfidence = Math.min(minConfidence, confidenceOf(productAnswer));

        JevResponse.Answer quantityAnswer = response.answer(Q_QUANTITY);
        Integer quantity = parseQuantity(quantityAnswer);
        if (quantity == null) {
            return DecisionResult.miss(DecisionResult.INTENT_ORDER, minConfidence, "缺少或非法的 quantity 答案");
        }
        minConfidence = Math.min(minConfidence, confidenceOf(quantityAnswer));

        List<String> optionNames = new ArrayList<>();
        for (String groupCode : state.groupCodesOf(product.code())) {
            MenuState.SpecGroupRef group = state.groupByCode().get(groupCode);
            if (group == null) {
                continue;
            }
            Double groupConfidence = group.multiSelect()
                    ? collectToppings(state, response, group, optionNames)
                    : collectSingleChoice(state, response, group, optionNames);
            if (groupConfidence == null) {
                return DecisionResult.miss(DecisionResult.INTENT_ORDER, minConfidence,
                        "必选规格缺失或越界：" + groupCode);
            }
            minConfidence = Math.min(minConfidence, groupConfidence);
        }

        double threshold = properties.confidenceThresholdOrDefault();
        if (minConfidence < threshold) {
            return DecisionResult.miss(DecisionResult.INTENT_ORDER, minConfidence,
                    "置信度 " + format(minConfidence) + " 低于阈值 " + format(threshold));
        }
        return DecisionResult.hit(product.code(), product.name(), optionNames, quantity, minConfidence);
    }

    /**
     * 单选组：必须拿到答案且下标合法。
     *
     * @return 该组的置信度；缺失或越界返回 {@code null}（调用方据此拒绝落草稿）
     */
    private Double collectSingleChoice(MenuState state,
                                       JevResponse response,
                                       MenuState.SpecGroupRef group,
                                       List<String> optionNames) {
        JevResponse.Answer answer = response.answer(groupQuestionName(group.code()));
        if (answer == null || answer.choice() == null) {
            return null;
        }
        Integer index = parseIndex(answer.choice());
        if (index == null || state.optionNameOf(group.code(), index) == null) {
            return null;
        }
        optionNames.add(state.optionNameOf(group.code(), index));
        return confidenceOf(answer);
    }

    /**
     * 多选组（加料）：逐项判定，缺答案按「不加」处理（加料本就是可选项）。
     *
     * @return 已作答各项的最小置信度；全未作答时返回 1.0（不加任何料是一个确定结论）
     */
    private Double collectToppings(MenuState state,
                                   JevResponse response,
                                   MenuState.SpecGroupRef group,
                                   List<String> optionNames) {
        double groupConfidence = 1.0;
        boolean any = false;
        for (MenuState.OptionRef option : group.options()) {
            JevResponse.Answer answer = response.answer(optionQuestionName(group.code(), option.index()));
            if (answer == null || answer.score() == null) {
                continue;
            }
            any = true;
            groupConfidence = Math.min(groupConfidence, confidenceOf(answer));
            if (answer.score() >= SELECTED_LEVEL) {
                optionNames.add(option.name());
            }
        }
        // 一个加料问题都没答：认为模型判定「不加料」，不因此阻断下单
        return any ? groupConfidence : 1.0;
    }

    /** 数量：必须落在合法区间内，越界视为缺失（不替顾客放大成上限）。 */
    private Integer parseQuantity(JevResponse.Answer answer) {
        if (answer == null || answer.choice() == null) {
            return null;
        }
        Integer quantity = parseIndex(answer.choice());
        if (quantity == null || quantity < OrderItemRequest.MIN_QUANTITY || quantity > OrderItemRequest.MAX_QUANTITY) {
            return null;
        }
        return quantity;
    }

    private Integer parseIndex(String text) {
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** confidence 缺省按 0 计：拿不准就别落草稿，宁可转澄清。 */
    private double confidenceOf(JevResponse.Answer answer) {
        if (answer == null || answer.confidence() == null) {
            return 0.0;
        }
        return answer.confidence();
    }

    private String groupQuestionName(String groupCode) {
        return GROUP_PREFIX + slug(groupCode);
    }

    private String optionQuestionName(String groupCode, int index) {
        return OPTION_PREFIX + slug(groupCode) + "_" + index;
    }

    /** 问题名归一：Jev 的问题名是我们自己给的字面量，统一成小写 ASCII 以便调试时肉眼对齐。 */
    private String slug(String text) {
        if (text == null) {
            return "unknown";
        }
        StringBuilder slug = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            slug.append(Character.isLetterOrDigit(c) && c < 128 ? Character.toLowerCase(c) : '_');
        }
        return slug.toString();
    }

    private String priceHint(String priceDelta) {
        if (priceDelta == null || priceDelta.startsWith("0.00") || priceDelta.startsWith("-0.00")) {
            return " (no extra charge)";
        }
        return priceDelta.startsWith("-") ? " (" + priceDelta + ")" : " (+" + priceDelta + ")";
    }

    private String format(double value) {
        return String.format("%.3f", value);
    }
}
