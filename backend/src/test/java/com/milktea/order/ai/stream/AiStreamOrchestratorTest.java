package com.milktea.order.ai.stream;

import com.milktea.order.ai.AiNarrator;
import com.milktea.order.ai.decision.DecisionOrchestrator;
import com.milktea.order.ai.decision.DecisionResult;
import com.milktea.order.ai.decision.PreferenceSnapshot;
import com.milktea.order.ai.dto.AiChatRequest;
import com.milktea.order.ai.ratelimit.CustomerRateLimiter;
import com.milktea.order.ai.service.AiChatService;
import com.milktea.order.ai.session.AiSessionEntity;
import com.milktea.order.ai.session.SessionService;
import com.milktea.order.ai.vo.AiChatVo;
import com.milktea.order.ai.vo.AiDraftVo;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.preference.service.PreferenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式编排测试：事件契约、并行顺序、超时与降级。
 *
 * <p>这里守的是三件容易在真实环境里「偶尔才出错」的事：</p>
 * <ol>
 *   <li><b>首字闸门真的生效</b>：草稿卡不得抢在承接话术之前到达——否则顾客会看到
 *       「先冒出卡片、再补一句收到」的错乱顺序；</li>
 *   <li><b>整体超时一定收尾</b>：无论中间哪一跳卡住，都必须在预算内推 {@code error}
 *       并关闭连接，不留悬挂的 SSE 连接；</li>
 *   <li><b>任何失败都降级而不是静默</b>：限流、非顾客、模型不可用都要以 {@code error} 事件收尾，
 *       前端据此复用既有错误处理。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AiStreamOrchestratorTest {

    private static final Long CUSTOMER = 7L;
    private static final String SESSION_UUID = "session-uuid-1";

    @Mock
    private AiChatService aiChatService;

    @Mock
    private DecisionOrchestrator decisionOrchestrator;

    @Mock
    private AiNarrator aiNarrator;

    @Mock
    private PreferenceService preferenceService;

    @Mock
    private SessionService sessionService;

    @Mock
    private CustomerRateLimiter rateLimiter;

    /** 真实线程池（≥2 线程：旁白与决策并行），测试结束即关闭。 */
    private ExecutorService executor;

    private AiStreamOrchestrator orchestrator;

    private final AtomicReference<RecordingEmitter> emitterRef = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
        orchestrator = new AiStreamOrchestrator(aiChatService, decisionOrchestrator, aiNarrator,
                preferenceService, sessionService, rateLimiter,
                new AiStreamWatchdog(), JsonMapper.builder().build(), executor) {
            @Override
            SseEmitter createEmitter() {
                RecordingEmitter emitter = new RecordingEmitter();
                emitterRef.set(emitter);
                return emitter;
            }
        };
        ReflectionTestUtils.setField(orchestrator, "timeoutSeconds", 20L);
        ReflectionTestUtils.setField(orchestrator, "firstDeltaGateMillis", 2000L);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("命中路径：承接话术先到、草稿卡随后，事件序列完整")
    void emitsNarratorThenCardWhenDecisionHits() {
        givenSessionWithNoPreference();
        // 旁白「流式」：50ms 后才吐第一个增量 —— 决策分支必须等它，否则卡片会抢先
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            sleep(50);
            onDelta.accept("收到，我来看看～");
            return null;
        }).when(aiNarrator).narrate(anyString(), any());
        when(decisionOrchestrator.decide(eq(SESSION_UUID), anyString(), any()))
                .thenReturn(DecisionResult.hit("P01", "珍珠奶茶", List.of("大杯", "半糖"), 1, 0.93));
        when(aiChatService.composeReply(eq(SESSION_UUID), anyString()))
                .thenReturn(AiChatVo.card(SESSION_UUID, "已放好", draft()));

        RecordingEmitter emitter = startStream();
        List<String> names = emitter.eventNames();

        // 两条 delta：先是旁白的承接话术，后是后端按草稿生成的确认话术（不经模型）
        assertEquals(List.of("stage", "stage", "delta", "stage", "decision", "delta", "card", "done"), names,
                "事件序列：阶段 → 承接话术 → 算价阶段 → 决策 → 确认话术 → 草稿卡 → 收尾");
        assertTrue(names.indexOf("delta") < names.indexOf("decision"), "承接话术必须先于决策下发（首字闸门）");
        assertTrue(names.indexOf("delta") < names.indexOf("card"), "承接话术必须先于草稿卡下发");

        List<RecordedEvent> events = emitter.parsed();
        List<String> deltas = dataOf(events, "delta");
        assertEquals(2, deltas.size(), deltas.toString());
        assertEquals("{\"text\":\"收到，我来看看～\"}", deltas.get(0),
                "data 只放载荷，不再包一层 event 壳");
        assertTrue(deltas.get(1).contains("已放好"), deltas.get(1));

        // 确认话术由代码按决策结果生成（不经模型）：既复述了杯子，也不复述金额
        verify(aiChatService).composeReply(eq(SESSION_UUID),
                org.mockito.ArgumentMatchers.argThat(text -> text.contains("已经帮你放好了：珍珠奶茶")
                        && text.contains("大杯/半糖") && text.contains("×1")
                        && text.contains("金额以卡片为准")));

        String decision = dataOf(events, "decision").get(0);
        assertTrue(decision.contains("\"source\":\"JEV\""), decision);
        assertTrue(decision.contains("\"productCode\":\"P01\""), decision);
        assertTrue(dataOf(events, "card").get(0).contains("15.00"), dataOf(events, "card").get(0));
        assertTrue(dataOf(events, "done").get(0).contains("\"replyType\":\"CARD\""),
                dataOf(events, "done").get(0));
    }

    @Test
    @DisplayName("未命中：转既有 GLM 链路，且整段下发不伪造流式")
    void fallsBackToGlmLinkWhenDecisionMisses() {
        givenSessionWithNoPreference();
        noNarratorOutput();
        when(decisionOrchestrator.decide(eq(SESSION_UUID), anyString(), any()))
                .thenReturn(DecisionResult.fallback("jev 熔断打开中"));
        when(aiChatService.chatInSession(any(), anyString())).thenReturn(AiChatVo.card(SESSION_UUID,
                "当前草稿共 1 项：珍珠奶茶(大杯) ×1，合计 15.00 元。", draft()));

        RecordingEmitter emitter = startStream();

        assertEquals(List.of("stage", "stage", "decision", "delta", "card", "done"), emitter.eventNames());
        assertTrue(dataOf(emitter.parsed(), "decision").get(0).contains("\"source\":\"GLM_FALLBACK\""),
                "降级来源必须可观测");
        assertTrue(dataOf(emitter.parsed(), "delta").get(0).contains("当前草稿共 1 项"));
        verify(decisionOrchestrator).decide(eq(SESSION_UUID), anyString(), any());
    }

    @Test
    @DisplayName("整体超时：推 error 并关闭连接，不留悬挂连接")
    void watchdogClosesStreamOnTimeout() {
        givenSessionWithNoPreference();
        ReflectionTestUtils.setField(orchestrator, "timeoutSeconds", 1L);
        // 旁白与决策双双卡住：看门狗必须把这一轮收掉
        doAnswer(invocation -> {
            sleep(5000);
            return null;
        }).when(aiNarrator).narrate(anyString(), any());
        when(decisionOrchestrator.decide(eq(SESSION_UUID), anyString(), any())).thenAnswer(invocation -> {
            sleep(5000);
            return DecisionResult.hit("P01", "珍珠奶茶", List.of(), 1, 0.9);
        });

        RecordingEmitter emitter = startStream(3000);

        assertTrue(emitter.eventNames().contains("error"), "超时必须推 error：" + emitter.eventNames());
        assertFalse(emitter.eventNames().contains("done"), "超时后不应再有 done");
        String error = dataOf(emitter.parsed(), "error").get(0);
        assertTrue(error.contains("\"code\":1008"), error);
        assertTrue(error.contains("fallbackText"), "错误载荷要带兜底话术，供前端直显");
        assertTrue(emitter.completed(), "超时后必须主动关闭连接");
    }

    @Test
    @DisplayName("模型不可用：以 1008 + fallbackText 收尾（复用非流式端点的降级口径）")
    void degradesToFallbackPayloadWhenModelUnavailable() {
        givenSessionWithNoPreference();
        noNarratorOutput();
        when(decisionOrchestrator.decide(eq(SESSION_UUID), anyString(), any()))
                .thenReturn(DecisionResult.fallback("jev 不可用"));
        when(aiChatService.chatInSession(any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.AI_UNAVAILABLE));

        RecordingEmitter emitter = startStream();

        assertEquals(List.of("stage", "stage", "decision", "error"), emitter.eventNames(),
                emitter.eventNames().toString());
        String error = dataOf(emitter.parsed(), "error").get(0);
        assertTrue(error.contains("\"code\":1008"), error);
        assertTrue(error.contains("AI 助手休息中"), "兜底话术与既有 LLD 3.4 文案一致：" + error);
    }

    @Test
    @DisplayName("触发限流：以 1009 收尾，且不进入编排")
    void rejectsWhenRateLimited() {
        when(rateLimiter.tryAcquire(CUSTOMER)).thenReturn(false);

        RecordingEmitter emitter = startStream();

        assertEquals(List.of("error"), emitter.eventNames());
        assertTrue(dataOf(emitter.parsed(), "error").get(0).contains("\"code\":1009"));
        verify(sessionService, never()).locateOrCreate(any(), anyLong());
        verify(aiNarrator, never()).narrate(anyString(), any());
    }

    @Test
    @DisplayName("非顾客身份：以 403 收尾")
    void rejectsNonCustomer() {
        // 不在 AuthContext 作用域内调用 → 身份为 null
        orchestrator.stream(request());

        RecordingEmitter emitter = emitterRef.get();
        assertEquals(List.of("error"), emitter.eventNames());
        assertTrue(dataOf(emitter.parsed(), "error").get(0).contains("\"code\":403"));
    }

    @Test
    @DisplayName("偏好读取失败：按无偏好继续，不影响整轮对话")
    void preferenceFailureDoesNotBreakTurn() {
        givenSessionBase();
        noNarratorOutput();
        when(preferenceService.snapshot(CUSTOMER)).thenThrow(new IllegalStateException("preference table down"));
        when(decisionOrchestrator.decide(eq(SESSION_UUID), anyString(), eq(PreferenceSnapshot.empty())))
                .thenReturn(DecisionResult.fallback("按无偏好继续"));
        when(aiChatService.chatInSession(any(), anyString())).thenReturn(AiChatVo.text(SESSION_UUID, "好的"));

        RecordingEmitter emitter = startStream();

        assertEquals(List.of("stage", "stage", "decision", "delta", "done"), emitter.eventNames());
        assertTrue(dataOf(emitter.parsed(), "done").get(0).contains("\"replyType\":\"TEXT\""));
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private void givenSessionWithNoPreference() {
        givenSessionBase();
        when(preferenceService.snapshot(CUSTOMER)).thenReturn(PreferenceSnapshot.empty());
    }

    private void givenSessionBase() {
        when(rateLimiter.tryAcquire(CUSTOMER)).thenReturn(true);
        AiSessionEntity session = new AiSessionEntity();
        session.setId(1L);
        session.setSessionUuid(SESSION_UUID);
        session.setCustomerId(CUSTOMER);
        when(sessionService.locateOrCreate(any(), eq(CUSTOMER))).thenReturn(session);
    }

    private void noNarratorOutput() {
        doAnswer(invocation -> null).when(aiNarrator).narrate(anyString(), any());
    }

    private AiChatRequest request() {
        AiChatRequest request = new AiChatRequest();
        request.setMessage("来一杯少糖的");
        return request;
    }

    /** 以顾客身份启动一轮流式对话，并等待收尾。 */
    private RecordingEmitter startStream() {
        return startStream(5000);
    }

    private RecordingEmitter startStream(long timeoutMillis) {
        AiChatRequest request = request();
        AuthContext.runWith(AuthContext.Principal.customer(CUSTOMER, "openid-1"),
                () -> orchestrator.stream(request));
        RecordingEmitter emitter = emitterRef.get();
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (emitter.eventNames().contains("done") || emitter.eventNames().contains("error")) {
                return emitter;
            }
            sleep(20);
        }
        fail("等待事件流收尾超时，已收到：" + emitter.eventNames());
        return emitter;
    }

    private List<String> dataOf(List<RecordedEvent> events, String name) {
        List<String> data = new ArrayList<>();
        for (RecordedEvent event : events) {
            if (event.name().equals(name)) {
                data.add(event.data());
            }
        }
        return data;
    }

    private static AiDraftVo draft() {
        return new AiDraftVo(
                List.of(new AiDraftVo.Item("珍珠奶茶", List.of("大杯", "半糖"), 1, "15.00", "15.00")),
                "15.00");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 单条已记录事件。 */
    private record RecordedEvent(String name, String data) {
    }

    /**
     * 记录型 SSE 发射器：把事件报文留在内存里供断言。
     *
     * <p><b>为什么覆写的是 {@code send(SseEventBuilder)} 而不是 {@code send(Object, MediaType)}</b>：
     * Spring 7 的 {@code SseEmitter.send(SseEventBuilder)} 内部用 {@code invokespecial} 直接调
     * 父类的私有 {@code send(Set)}，<b>绕过了</b>公开的 {@code send(Object, MediaType)}——
     * 覆写后者一个字都收不到（本测试第一版就是这么失败的）。而 {@code send(SseEventBuilder)}
     * 本身是公开可覆写的，且它的入参能 {@code build()} 出报文内容，是唯一有效的拦截点。</p>
     *
     * <p>覆写后不再需要 handler，也就不会走 {@code ResponseBodyEmitter} 的早发缓冲
     * （那里有 256 条的上限，且内容要等 handler 初始化才可见）。</p>
     */
    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> raw = new CopyOnWriteArrayList<>();

        private volatile boolean completed;

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            // build() 会把「event:/id: 分帧」与「data: 载荷」拆成多个条目，
            // 它们是同一个事件的不同片段，必须拼回一条——否则解析出的 data 恒为空。
            StringBuilder framed = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType entry : builder.build()) {
                framed.append(entry.getData());
            }
            raw.add(framed.toString());
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable ex) {
            completed = true;
        }

        boolean completed() {
            return completed;
        }

        List<String> eventNames() {
            List<String> names = new ArrayList<>();
            for (RecordedEvent event : parsed()) {
                names.add(event.name());
            }
            return names;
        }

        List<RecordedEvent> parsed() {
            List<RecordedEvent> events = new ArrayList<>();
            for (String chunk : raw) {
                String name = null;
                StringBuilder data = new StringBuilder();
                for (String line : chunk.split("\n")) {
                    if (line.startsWith("event:")) {
                        name = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring("data:".length()).trim());
                    }
                }
                if (name != null) {
                    events.add(new RecordedEvent(name, data.toString()));
                }
            }
            return events;
        }
    }
}
