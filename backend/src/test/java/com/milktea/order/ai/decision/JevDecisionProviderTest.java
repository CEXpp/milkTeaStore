package com.milktea.order.ai.decision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 决策解释层测试：把 Jev 的答案翻译成「一杯具体的奶茶」，或明确拒绝下单。
 *
 * <p><b>这些用例全是"不许下单"的路径</b>——因为本方案的风险不对称：
 * 少下一单只是多聊一句，猜错一单是实打实的错误出品。故所有边界都朝「拒」的方向断言。</p>
 */
@ExtendWith(MockitoExtension.class)
class JevDecisionProviderTest {

    private static final double THRESHOLD = 0.72;

    @Mock
    private JevClient jevClient;

    private JevDecisionProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JevDecisionProvider(jevClient, properties(THRESHOLD));
    }

    @Test
    @DisplayName("全项高置信：命中并按组顺序还原中文商品名与规格名")
    void hitsWhenEverythingIsConfident() {
        when(jevClient.ask(any())).thenReturn(response(0.95, 0.95, 0.95));

        DecisionResult result = provider.decide(sampleState());

        assertTrue(result.hit());
        assertEquals("P01", result.productCode());
        assertEquals("珍珠奶茶", result.productName(), "必须还原成中文名，落草稿靠它做精确匹配");
        assertEquals(List.of("大杯", "半糖", "珍珠"), result.optionNames());
        assertEquals(1, result.quantity());
        assertEquals(0.95, result.confidence(), 1e-9);
        assertEquals(DecisionResult.Source.JEV, result.source());
    }

    @Test
    @DisplayName("state 原样进入请求，问题键为 ASCII 编码")
    void passesStateAndAsciiQuestionNames() {
        when(jevClient.ask(any())).thenReturn(response(0.95, 0.95, 0.95));

        provider.decide(sampleState());

        ArgumentCaptor<JevRequest> captor = ArgumentCaptor.forClass(JevRequest.class);
        verify(jevClient).ask(captor.capture());
        JevRequest request = captor.getValue();
        assertEquals("MENU ...\n\nCUSTOMER REQUEST:\n来一杯", request.state());
        assertTrue(request.questions().keySet().containsAll(
                List.of("intent", "ready_to_order", "product", "quantity", "grp_cup_size", "grp_sweetness")),
                "问题键：" + request.questions().keySet());
        assertTrue(request.questions().containsKey("opt_topping_0"), "多选组应逐项出题");
    }

    @Test
    @DisplayName("商品编码不在在售集合：拒绝落草稿（防模型幻觉编码）")
    void rejectsUnknownProductCode() {
        JevResponse response = response(0.95, 0.95, 0.95);
        JevResponse hallucinated = new JevResponse(response.model(), withAnswers(response, "product", "P99"), response.usage());
        when(jevClient.ask(any())).thenReturn(hallucinated);

        DecisionResult result = provider.decide(sampleState());

        assertFalse(result.hit());
        assertTrue(result.reason().contains("商品编码不在在售集合"), result.reason());
    }

    @Test
    @DisplayName("聚合置信度取所依赖各项的最小值：有一项没底就不下单")
    void aggregatesConfidenceByMinimum() {
        // 意图与商品都很确定，但数量项只有 0.5 —— 整杯就是不确定
        when(jevClient.ask(any())).thenReturn(response(0.99, 0.5, 0.99));

        DecisionResult result = provider.decide(sampleState());

        assertFalse(result.hit());
        assertEquals(0.5, result.confidence(), 1e-9);
        assertTrue(result.reason().contains("低于阈值"), result.reason());
    }

    @Test
    @DisplayName("意图非下单：直接转 GLM 链路，不落草稿")
    void routesNonOrderIntentAway() {
        Map<String, JevResponse.Answer> answers = baseAnswers(0.95, 0.95, 0.95);
        answers.put("intent", choice("OTHER", 0.98));
        when(jevClient.ask(any())).thenReturn(new JevResponse("jev-test", answers, usage()));

        DecisionResult result = provider.decide(sampleState());

        assertFalse(result.hit());
        assertEquals("OTHER", result.intent());
        assertTrue(result.reason().contains("意图非下单"));
    }

    @Test
    @DisplayName("信息不足（ready_to_order 概率偏低）：转澄清而不是硬点")
    void asksForClarificationWhenNotReady() {
        Map<String, JevResponse.Answer> answers = baseAnswers(0.95, 0.95, 0.95);
        answers.put("ready_to_order", noul(0.31));
        when(jevClient.ask(any())).thenReturn(new JevResponse("jev-test", answers, usage()));

        DecisionResult result = provider.decide(sampleState());

        assertFalse(result.hit());
        assertTrue(result.reason().contains("信息不足以直接下单"), result.reason());
    }

    @Test
    @DisplayName("单选组答案缺失：拒绝（宁可问一句，不可替顾客选杯型）")
    void rejectsMissingRequiredGroupAnswer() {
        Map<String, JevResponse.Answer> answers = baseAnswers(0.95, 0.95, 0.95);
        answers.remove("grp_sweetness");
        when(jevClient.ask(any())).thenReturn(new JevResponse("jev-test", answers, usage()));

        DecisionResult result = provider.decide(sampleState());

        assertFalse(result.hit());
        assertTrue(result.reason().contains("必选规格缺失或越界"), result.reason());
    }

    @Test
    @DisplayName("规格下标越界：拒绝，不去猜测附近选项")
    void rejectsOutOfRangeOptionIndex() {
        Map<String, JevResponse.Answer> answers = baseAnswers(0.95, 0.95, 0.95);
        answers.put("grp_cup_size", choice("7", 0.95));
        when(jevClient.ask(any())).thenReturn(new JevResponse("jev-test", answers, usage()));

        DecisionResult result = provider.decide(sampleState());

        assertFalse(result.hit());
        assertTrue(result.reason().contains("必选规格缺失或越界"));
    }

    @Test
    @DisplayName("多选组：只有 score 命中的加料进入规格，未命中即不加")
    void collectsOnlySelectedToppings() {
        Map<String, JevResponse.Answer> withPearlAnswers = baseAnswers(0.95, 0.95, 0.95);
        withPearlAnswers.put("opt_topping_0", score(1.0, 0.99));
        when(jevClient.ask(any())).thenReturn(new JevResponse("jev-test", withPearlAnswers, usage()));

        DecisionResult withPearl = provider.decide(sampleState());
        assertTrue(withPearl.hit());
        assertTrue(withPearl.optionNames().contains("珍珠"));

        Map<String, JevResponse.Answer> withoutPearlAnswers = baseAnswers(0.95, 0.95, 0.95);
        withoutPearlAnswers.put("opt_topping_0", score(0.0, 0.99));
        when(jevClient.ask(any())).thenReturn(new JevResponse("jev-test", withoutPearlAnswers, usage()));

        DecisionResult withoutPearl = provider.decide(sampleState());
        assertTrue(withoutPearl.hit());
        assertFalse(withoutPearl.optionNames().contains("珍珠"), "未命中加料不得进规格");
    }

    @Test
    @DisplayName("空菜单：本地短路，不请求决策")
    void emptyMenuShortCircuits() {
        MenuState empty = new MenuState("MENU\n(no on-sale product)\n", "", "CUSTOMER REQUEST:\n来一杯",
                Map.of(), Map.of(), false);

        DecisionResult result = provider.decide(empty);

        assertFalse(result.hit());
        verify(jevClient, org.mockito.Mockito.never()).ask(any());
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private JevProperties properties(double threshold) {
        return new JevProperties(true, "http://127.0.0.1:1", "test-key", "/v1/systemone", "jev-1.13.0",
                2, 3, 6000, threshold, 3, 1000, 5, 60);
    }

    private MenuState sampleState() {
        return new MenuState("MENU ...", "", "CUSTOMER REQUEST:\n来一杯",
                Map.of("P01", new MenuState.ProductRef("P01", "珍珠奶茶", "Pearl milk tea", "12.00",
                        List.of("CUP_SIZE", "SWEETNESS", "TOPPING"))),
                Map.of(
                        "CUP_SIZE", new MenuState.SpecGroupRef("CUP_SIZE", "杯型", false, List.of(
                                option(0, 1L, "中杯", "Regular", "0.00"),
                                option(1, 2L, "大杯", "Large", "3.00"))),
                        "SWEETNESS", new MenuState.SpecGroupRef("SWEETNESS", "甜度", false, List.of(
                                option(0, 8L, "全糖", "Full sugar", "0.00"),
                                option(1, 10L, "半糖", "Half sugar", "0.00"))),
                        "TOPPING", new MenuState.SpecGroupRef("TOPPING", "加料", true, List.of(
                                option(0, 13L, "珍珠", "Pearl", "2.00")))),
                false);
    }

    private MenuState.OptionRef option(int index, Long id, String name, String label, String priceDelta) {
        return new MenuState.OptionRef(index, id, name, label, priceDelta);
    }

    /**
     * 一份「大杯 / 半糖 / 加珍珠 / 数量 1」的基线答案，可局部覆盖。
     *
     * <p>规格各项的置信度取 0.99：这样断言聚合置信度时，最小值就由 intent / product /
     * quantity 三者决定，用例意图一眼可见，不必反推「到底是谁把最小值拉下去了」。</p>
     */
    private Map<String, JevResponse.Answer> baseAnswers(double intent, double product, double quantity) {
        Map<String, JevResponse.Answer> answers = new LinkedHashMap<>();
        answers.put("intent", choice("ORDER", intent));
        answers.put("ready_to_order", noul(0.93));
        answers.put("product", choice("P01", product));
        answers.put("quantity", choice("1", quantity));
        answers.put("grp_cup_size", choice("1", 0.99));
        answers.put("grp_sweetness", choice("1", 0.99));
        answers.put("opt_topping_0", score(1.0, 0.99));
        return answers;
    }

    /** 基线答案，但把数量项的置信度换成指定值（用于最小置信度聚合断言）。 */
    private JevResponse response(double intent, double quantity, double product) {
        Map<String, JevResponse.Answer> answers = baseAnswers(intent, product, 0.9);
        answers.put("quantity", choice("1", quantity));
        return new JevResponse("jev-test", answers, usage());
    }

    private Map<String, JevResponse.Answer> withAnswers(JevResponse source, String name, String choiceKey) {
        Map<String, JevResponse.Answer> answers = new LinkedHashMap<>(source.answers());
        answers.put(name, choice(choiceKey, 0.95));
        return answers;
    }

    private JevResponse.Answer choice(String key, double confidence) {
        return new JevResponse.Answer("choice", key, null, null, Map.of(key, confidence), confidence, null);
    }

    private JevResponse.Answer noul(double value) {
        return new JevResponse.Answer("noul", null, null, null, Map.of(), null, value);
    }

    private JevResponse.Answer score(double value, double confidence) {
        return new JevResponse.Answer("score", null, value, Map.of("0", "not selected", "1", "selected"),
                Map.of(), confidence, null);
    }

    private JevResponse.Usage usage() {
        return new JevResponse.Usage(120, 8);
    }
}
