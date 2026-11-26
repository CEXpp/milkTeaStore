package com.milktea.order.ai.decision;

import com.milktea.order.ai.draft.DraftItem;
import com.milktea.order.ai.session.SessionService;
import com.milktea.order.ai.tools.DraftOrderTool;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 决策编排测试：这里守的是<b>安全边界</b>而非功能。
 *
 * <p>三件事必须成立：</p>
 * <ol>
 *   <li><b>只有命中才写草稿</b>，且写完要回读验证——不信 {@code DraftOrderTool} 的中文返回值文本；</li>
 *   <li><b>任何失败都降级</b>（未启用 / 抛异常 / 组装失败 / 落草稿失败），绝不把 AI 点单搞不可用；</li>
 *   <li><b>决策层手里没有模型</b>：草稿落库这条路径上不存在任何 chat model，
 *       这是「AI 只做表述层、不做计算层」在装配层面的硬保证。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DecisionOrchestratorTest {

    private static final String SESSION = "session-uuid-1";

    private static final DecisionResult HIT = DecisionResult.hit(
            "P01", "珍珠奶茶", List.of("大杯", "半糖"), 2, 0.9);

    @Mock
    private DecisionProvider decisionProvider;

    @Mock
    private MenuStateAssembler menuStateAssembler;

    @Mock
    private SessionService sessionService;

    @Mock
    private DraftOrderTool draftOrderTool;

    private DecisionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new DecisionOrchestrator(enabledProperties(), decisionProvider,
                menuStateAssembler, sessionService, draftOrderTool);
    }

    @Test
    @DisplayName("未启用或未配密钥：本地短路，不组装上下文也不请求决策")
    void disabledShortCircuits() {
        JevProperties off = new JevProperties(false, "http://127.0.0.1:1", "k", "/v1/systemone",
                "jev-1.13.0", 1, 1, 6000, 0.72, 1, 100, 1, 60);
        DecisionOrchestrator disabled = new DecisionOrchestrator(off, decisionProvider,
                menuStateAssembler, sessionService, draftOrderTool);

        DecisionResult result = disabled.decide(SESSION, "来一杯", PreferenceSnapshot.empty());

        assertFalse(result.hit());
        assertEquals(DecisionResult.Source.DISABLED, result.source());
        verify(decisionProvider, never()).decide(any());
        verify(menuStateAssembler, never()).assemble(anyString(), any(), anyList());
    }

    @Test
    @DisplayName("命中且草稿落成：返回 hit=true")
    void hitWritesDraftAndVerifies() {
        givenState();
        when(decisionProvider.decide(any())).thenReturn(HIT);
        // 第一次是组装用的读草稿，第二次是落库后的回读验证
        when(sessionService.loadDraft(SESSION)).thenReturn(
                List.of(),
                List.of(new DraftItem("珍珠奶茶", List.of("半糖", "大杯"), 2)));

        DecisionResult result = orchestrator.decide(SESSION, "大杯半糖两杯", PreferenceSnapshot.empty());

        assertTrue(result.hit());
        assertEquals(DecisionResult.Source.JEV, result.source());
        assertEquals(2, result.quantity());
        verify(draftOrderTool).updateDraftOrder(eq("珍珠奶茶"), eq(List.of("大杯", "半糖")), eq(2), eq(SESSION));
    }

    @Test
    @DisplayName("命中但草稿未落成：以约束层为准，转 GLM 链路（不信返回值文本）")
    void hitWithoutDraftFallsBackToGlmPath() {
        givenState();
        when(decisionProvider.decide(any())).thenReturn(HIT);
        when(sessionService.loadDraft(SESSION)).thenReturn(List.of(), List.of());

        DecisionResult result = orchestrator.decide(SESSION, "大杯半糖两杯", PreferenceSnapshot.empty());

        assertFalse(result.hit());
        assertEquals(DecisionResult.Source.JEV, result.source(), "这是决策结论层面的未命中，不是链路降级");
        assertNotNull(result.reason());
    }

    @Test
    @DisplayName("命中但草稿写入抛异常：吞掉异常并转 GLM 链路")
    void draftWriteFailureIsContained() {
        givenState();
        when(decisionProvider.decide(any())).thenReturn(HIT);
        when(sessionService.loadDraft(SESSION)).thenReturn(List.of());
        when(draftOrderTool.updateDraftOrder(anyString(), anyList(), anyInt(), anyString()))
                .thenThrow(new IllegalStateException("db down"));

        DecisionResult result = orchestrator.decide(SESSION, "大杯半糖两杯", PreferenceSnapshot.empty());

        assertFalse(result.hit());
        assertNotNull(result.reason());
    }

    @Test
    @DisplayName("决策链路不可用：降级为 GLM_FALLBACK，对外不抛异常")
    void unavailableDegradesToFallback() {
        givenState();
        when(decisionProvider.decide(any()))
                .thenThrow(new JevUnavailableException("jev 熔断打开中，本地短路降级"));
        when(sessionService.loadDraft(SESSION)).thenReturn(List.of());

        DecisionResult result = orchestrator.decide(SESSION, "来一杯", PreferenceSnapshot.empty());

        assertFalse(result.hit());
        assertEquals(DecisionResult.Source.GLM_FALLBACK, result.source());
        verify(draftOrderTool, never()).updateDraftOrder(anyString(), anyList(), anyInt(), anyString());
    }

    @Test
    @DisplayName("决策层未预期异常同样被兜住，不让 AI 点单不可用")
    void unexpectedExceptionIsContained() {
        givenState();
        when(decisionProvider.decide(any())).thenThrow(new RuntimeException("boom"));
        when(sessionService.loadDraft(SESSION)).thenReturn(List.of());

        DecisionResult result = orchestrator.decide(SESSION, "来一杯", PreferenceSnapshot.empty());

        assertEquals(DecisionResult.Source.GLM_FALLBACK, result.source());
    }

    @Test
    @DisplayName("组装上下文失败：降级而非报错")
    void assemblyFailureDegrades() {
        when(menuStateAssembler.assemble(anyString(), any(), anyList()))
                .thenThrow(new IllegalStateException("menu query failed"));

        DecisionResult result = orchestrator.decide(SESSION, "来一杯", PreferenceSnapshot.empty());

        assertEquals(DecisionResult.Source.GLM_FALLBACK, result.source());
        verify(decisionProvider, never()).decide(any());
    }

    @Test
    @DisplayName("模型的正常结论（不下单）原样透传，不写草稿")
    void plainMissPassesThrough() {
        givenState();
        when(decisionProvider.decide(any()))
                .thenReturn(DecisionResult.miss(DecisionResult.INTENT_OTHER, 0.95, "意图非下单：OTHER"));
        when(sessionService.loadDraft(SESSION)).thenReturn(List.of());

        DecisionResult result = orchestrator.decide(SESSION, "把我的单退了", PreferenceSnapshot.empty());

        assertFalse(result.hit());
        assertEquals(DecisionResult.INTENT_OTHER, result.intent());
        assertEquals(DecisionResult.Source.JEV, result.source());
        verify(draftOrderTool, never()).updateDraftOrder(anyString(), anyList(), anyInt(), anyString());
    }

    @Test
    @DisplayName("决策层手里不得有任何 chat model（AI 只做表述层，不做计算层）")
    void decisionLayerHoldsNoChatModel() {
        for (Field field : DecisionOrchestrator.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            assertFalse(ChatModel.class.isAssignableFrom(type),
                    "决策编排不应持有模型：" + field.getName());
            assertFalse(type.getName().contains("langchain4j"),
                    "决策编排不应依赖 LangChain4j：" + field.getName() + " -> " + type.getName());
        }
        assertNull(findField(DecisionOrchestrator.class, "orderAssistant"),
                "命中路径不得经过带工具的对话助手");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private void givenState() {
        when(menuStateAssembler.assemble(anyString(), any(), anyList())).thenReturn(sampleState());
    }

    private Field findField(Class<?> type, String name) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        return null;
    }

    private JevProperties enabledProperties() {
        return new JevProperties(true, "http://127.0.0.1:1", "test-key", "/v1/systemone", "jev-1.13.0",
                2, 3, 6000, 0.72, 3, 1000, 5, 60);
    }

    private MenuState sampleState() {
        return new MenuState("MENU ...", "", "CUSTOMER REQUEST:\n来一杯",
                Map.of("P01", new MenuState.ProductRef("P01", "珍珠奶茶", "Pearl milk tea", "12.00",
                        List.of("CUP_SIZE", "SWEETNESS"))),
                Map.of(), false);
    }
}
