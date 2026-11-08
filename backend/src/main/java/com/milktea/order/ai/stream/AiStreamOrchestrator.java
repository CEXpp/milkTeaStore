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
import com.milktea.order.ai.vo.AiFallbackVo;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.preference.service.PreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 并行流式编排：一句承接话术先回显，决策与草稿卡随后追加。
 *
 * <h2>两条分支并发</h2>
 * <ul>
 *   <li><b>分支 A（表述）</b>：{@link AiNarrator} 走 GLM 流式、不挂工具，增量文本立刻以下发；
 *       首字延迟由它决定，Jev 的往返被完全隐藏；</li>
 *   <li><b>分支 B（决策）</b>：{@link DecisionOrchestrator} 组装全量菜单 state、请求裁决并落草稿。</li>
 * </ul>
 *
 * <h2>为什么要有「首字闸门」</h2>
 * <p>两条分支谁先到达是不确定的。若草稿卡抢在承接话术之前到达，顾客会看到「先冒出一张卡、
 * 再补一句『收到』」的错乱顺序。因此在推 {@code decision}/{@code card} 之前，
 * 先等分支 A 的<b>首个增量</b>最多 {@code ai.stream.first-delta-gate-millis}（默认 2s）。
 * 这个等待有两个安全边界：分支 A 结束（含失败）也会放行，超时也会放行——
 * 旁白不该成为主链路的阻塞点。</p>
 *
 * <h2>为什么拒绝「伪造流式」</h2>
 * <p>走 GLM 链路时其回复是一次性拿到的（{@code OrderAssistant} 无流式），这里就<b>整段</b>
 * 作为一条 {@code delta} 下发，不切成小块假装流式。伪流式除了好看没有价值，
 * 却会让人误以为「模型在逐字生成」，从而在排查延迟时走到错误的方向。</p>
 *
 * <h2>线程与超时</h2>
 * <p>控制器线程只做鉴权/限流/会话定位，随后立刻返回 {@code SseEmitter}；编排跑在
 * {@code aiStreamExecutor}，并挂 {@link AiStreamWatchdog} 的整体超时。
 * 超时或客户端断开后，{@link StreamContext} 会把 {@code finished} 置位，
 * 后续事件一律丢弃——包括仍在阻塞的模型调用的回调。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiStreamOrchestrator {

    private final AiChatService aiChatService;
    private final DecisionOrchestrator decisionOrchestrator;
    private final AiNarrator aiNarrator;
    private final PreferenceService preferenceService;
    private final SessionService sessionService;
    private final CustomerRateLimiter rateLimiter;
    private final AiStreamWatchdog watchdog;
    private final ObjectMapper objectMapper;
    private final ExecutorService aiStreamExecutor;

    /** 整体超时（秒）：这一轮对话必须在这个预算内给出结论并关闭连接。 */
    @Value("${ai.stream.timeout-seconds:20}")
    private long timeoutSeconds;

    /** 首字闸门（毫秒）：等承接话术首个增量最多多久。 */
    @Value("${ai.stream.first-delta-gate-millis:2000}")
    private long firstDeltaGateMillis;

    /**
     * 开启一轮流式对话。
     *
     * <p>鉴权、限流、会话定位都在<b>当前线程同步完成</b>：这三步要么很快、要么必须让调用方
     * 立刻知道失败（403 / 1009），没有流式的必要。失败时同样以 {@code error} 事件收尾，
     * 而不是抛异常——本端点的响应类型固定是 SSE，前端只认事件流。</p>
     *
     * @param request 对话请求（sessionId 可空 + message）
     * @return 交给 Spring MVC 的 SSE 发射器
     */
    public SseEmitter stream(AiChatRequest request) {
        StreamContext context = new StreamContext(createEmitter(), objectMapper);
        // 客户端断开要尽早感知：否则只能等下一次 send 失败才发现，
        // 而那时模型调用与草稿写入可能已经做完了（白花额度、还改了顾客的草稿）
        context.bindEmitterCallbacks();

        AuthContext.Principal principal = AuthContext.get();
        if (principal == null || !principal.isCustomer()) {
            context.fail(ErrorCode.FORBIDDEN.getCode(), "仅顾客可使用 AI 点单", null);
            return context.emitter();
        }
        Long customerId = principal.getCustomerId();
        if (!rateLimiter.tryAcquire(customerId)) {
            // LLD 9.2：按顾客限流 10 次/分钟，保护模型额度；流式与非流式共用同一份额度
            log.warn("[AI-STREAM] 触发限流：customerId={} limit={}/分钟",
                    customerId, rateLimiter.limitPerMinute());
            context.fail(ErrorCode.RATE_LIMITED.getCode(), ErrorCode.RATE_LIMITED.getMessage(), null);
            return context.emitter();
        }

        AiSessionEntity session;
        try {
            session = sessionService.locateOrCreate(request.getSessionId(), customerId);
        } catch (BusinessException e) {
            context.fail(e.getCode(), e.getMessage(), null);
            return context.emitter();
        } catch (Exception e) {
            // 查库异常等非业务失败也必须以事件收尾：本端点的响应类型固定是 SSE，
            // 抛出去会让前端收到 JSON 500 而不是 error 事件，两条链路的分支就此分叉
            log.warn("[AI-STREAM] 会话定位失败：{}", e.getMessage());
            context.fail(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage(), null);
            return context.emitter();
        }

        String message = request.getMessage();
        try {
            aiStreamExecutor.execute(() -> run(context, session, message, customerId));
        } catch (RejectedExecutionException e) {
            // 队列与线程都满了：明确拒绝，不让请求挂在队列里等到连接超时
            log.warn("[AI-STREAM] 流式编排队列已满，拒绝任务", e);
            context.fail(ErrorCode.RATE_LIMITED.getCode(), "当前咨询的人有点多，请稍后再试", null);
        }
        return context.emitter();
    }

    /**
     * 创建 SSE 发射器。
     *
     * <p>{@code new SseEmitter(0L)} 即「不超时」，与既有订单状态通道同一策略：本通道是
     * 请求级一次性连接，随 {@code done}/{@code error} 主动 complete，
     * 因此它的生命周期由编排层与看门狗管理，而不是由容器的异步超时管理。</p>
     *
     * <p>留成可覆盖的方法，是为了让单测能换成「记录型」发射器来断言事件序列——
     * 否则事件流只能靠起完整的 MockMvc 异步请求来验证，成本与脆弱度都会高一个量级。</p>
     */
    SseEmitter createEmitter() {
        return new SseEmitter(0L);
    }

    /** 池内执行：两条分支并发 + 事件下发 + 收尾。 */
    private void run(StreamContext context, AiSessionEntity session, String message, Long customerId) {
        String sessionUuid = session.getSessionUuid();
        long startedAt = System.currentTimeMillis();
        context.armWatchdog(watchdog, Math.max(timeoutSeconds, 1L) * 1000L);
        try {
            context.send(AiStreamEvent.stage(AiStreamEvent.STAGE_UNDERSTAND));

            // 分支 A：旁白（独立池线程；池满则直接放行闸门，不阻塞决策）
            CountDownLatch firstDelta = context.firstDeltaLatch();
            try {
                aiStreamExecutor.execute(() -> narrate(context, message, firstDelta));
            } catch (RejectedExecutionException e) {
                firstDelta.countDown();
            }

            // 分支 B：决策（本线程）
            if (context.isFinished()) {
                // 已超时或客户端已断开：不再花模型额度，也不去改顾客的草稿
                log.info("[AI-STREAM] 编排在决策前已结束（超时或客户端断开），跳过后续步骤");
                return;
            }
            context.send(AiStreamEvent.stage(AiStreamEvent.STAGE_PICK));
            DecisionResult decision = decisionOrchestrator.decide(sessionUuid, message, safePreference(customerId));
            awaitFirstDelta(firstDelta);
            if (context.isFinished()) {
                log.info("[AI-STREAM] 编排在回复前已结束（超时或客户端断开），跳过回复下发");
                return;
            }

            String replyType;
            if (decision.hit()) {
                context.send(AiStreamEvent.stage(AiStreamEvent.STAGE_PRICE));
                context.send(AiStreamEvent.decision(AiStreamEvent.SOURCE_JEV,
                        decision.confidence(), decision.productCode()));
                AiChatVo composed = aiChatService.composeReply(sessionUuid, confirmationText(decision));
                if (AiChatVo.TYPE_TEXT.equals(composed.replyType())) {
                    // 草稿条目全部不可计价时卡片会退化为 TEXT，此时不能再让顾客去点「立即支付」
                    composed = AiChatVo.text(sessionUuid,
                            "已经帮你记下了，但这一杯现在算不出价格，请到菜单页手动点单或再试一次。");
                }
                replyType = pushReply(context, composed);
            } else {
                // 未命中或降级：交既有 GLM 链路，行为与升级前一致
                context.send(AiStreamEvent.decision(AiStreamEvent.SOURCE_GLM, 0.0, null));
                replyType = pushReply(context, aiChatService.chatInSession(session, message));
            }

            sessionService.refreshExpiry(session);
            // done 与收尾必须在同一次 CAS 内完成：否则看门狗可能正好插在两句之间，
            // 客户端会先收到 done 再收到 error，事件契约出现二义
            context.finishWith(AiStreamEvent.done(sessionUuid, replyType));
            log.info("[AI-STREAM] 完成 sessionUuid={} hit={} replyType={} elapsedMs={}",
                    sessionUuid, decision.hit(), replyType, System.currentTimeMillis() - startedAt);
        } catch (BusinessException e) {
            // 既有链路的业务失败（1008 等）：与非流式端点同一口径，带兜底话术
            context.fail(e.getCode(), e.getMessage(), AiFallbackVo.FALLBACK_TEXT);
        } catch (Exception e) {
            log.warn("[AI-STREAM] 流式编排异常：{}", e.getMessage(), e);
            context.fail(ErrorCode.AI_UNAVAILABLE.getCode(), ErrorCode.AI_UNAVAILABLE.getMessage(),
                    AiFallbackVo.FALLBACK_TEXT);
        }
    }

    /** 旁白分支：逐段下发增量，并保证无论成败都放行闸门。 */
    private void narrate(StreamContext context, String message, CountDownLatch firstDelta) {
        try {
            aiNarrator.narrate(message, delta -> {
                context.send(AiStreamEvent.delta(delta));
                firstDelta.countDown();
            });
        } finally {
            // 模型不可用或没有输出时也要放行，否则决策分支会白等满闸门时长
            firstDelta.countDown();
        }
    }

    /** 等承接话术的首个增量，最多 {@code first-delta-gate-millis}。 */
    private void awaitFirstDelta(CountDownLatch firstDelta) {
        try {
            firstDelta.await(Math.max(firstDeltaGateMillis, 1L), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 下发一条完整回复（文本增量 + 可选卡片），返回回复形态供 {@code done} 事件使用。 */
    private String pushReply(StreamContext context, AiChatVo vo) {
        if (vo.text() != null && !vo.text().isBlank()) {
            context.send(AiStreamEvent.delta(vo.text()));
        }
        if (AiChatVo.TYPE_CARD.equals(vo.replyType()) && vo.draft() != null) {
            context.send(AiStreamEvent.card(vo.draft().items(), vo.draft().totalAmount()));
        }
        return vo.replyType();
    }

    /**
     * 命中决策时的确认话术——<b>由代码生成，不经过模型</b>。
     *
     * <p>理由与「卡片金额不经模型手」完全一致：这句话要复述顾客即将支付的东西，
     * 一旦由模型生成，就多了一个「说成另一杯」的可能，而它没有任何收益
     * （顾客要的信息在卡片上已经有了）。金额也刻意不复述，只指向卡片——
     * 两处各写一个数字，迟早会出现对不上的那一版。</p>
     */
    private String confirmationText(DecisionResult decision) {
        StringBuilder text = new StringBuilder("已经帮你放好了：").append(decision.productName());
        if (decision.optionNames() != null && !decision.optionNames().isEmpty()) {
            text.append("（").append(String.join("/", decision.optionNames())).append("）");
        }
        return text.append(" ×").append(decision.quantity())
                .append("。金额以卡片为准，确认没问题就点下面的「立即支付」。")
                .toString();
    }

    /** 偏好读取失败只降级为「这轮没有默认偏好」，绝不能影响整轮对话。 */
    private PreferenceSnapshot safePreference(Long customerId) {
        try {
            return preferenceService.snapshot(customerId);
        } catch (Exception e) {
            log.warn("[AI-STREAM] 偏好读取失败，按无偏好处理：{}", e.getMessage());
            return PreferenceSnapshot.empty();
        }
    }

    /**
     * 一次流式响应的生命周期容器：完成标记、首字闸门与超时句柄。
     *
     * <p>{@code finished} 是唯一的「还能不能发」判据，由 CAS 保证只有一方能把连接收掉
     * （正常收尾、看门狗超时、客户端断开三方可能同时到达）。所有下发都先看这个标记——
     * 否则一个已经关闭的 emitter 会持续抛异常，把日志刷满。</p>
     */
    private static final class StreamContext {

        private final SseEmitter emitter;
        private final ObjectMapper objectMapper;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final CountDownLatch firstDelta = new CountDownLatch(1);

        private volatile ScheduledFuture<?> watchdogFuture;

        StreamContext(SseEmitter emitter, ObjectMapper objectMapper) {
            this.emitter = emitter;
            this.objectMapper = objectMapper;
        }

        SseEmitter emitter() {
            return emitter;
        }

        CountDownLatch firstDeltaLatch() {
            return firstDelta;
        }

        /**
         * 注册连接生命周期回调：客户端断开或容器报错时立即置位 {@code finished}。
         *
         * <p>不注册的话，「客户端已走」这件事只有等到下一次 {@code send} 失败才会被发现，
         * 而那时决策分支可能已经把模型调完、把草稿也改了——白花额度，还改动了顾客购物车。</p>
         *
         * <p>这些回调只置位、不再调用 {@code complete()}：连接已经不在了，
         * 再 complete 只会抛异常。看门狗也会一并取消——没人等着这个结果了。</p>
         */
        void bindEmitterCallbacks() {
            emitter.onCompletion(this::markFinished);
            emitter.onTimeout(this::markFinished);
            emitter.onError(error -> markFinished());
        }

        /** 本轮是否已结束（超时 / 客户端断开 / 正常收尾）。 */
        boolean isFinished() {
            return finished.get();
        }

        /** 挂整体超时：到点仍未结束则推 error 并关闭连接。 */
        void armWatchdog(AiStreamWatchdog watchdog, long timeoutMillis) {
            watchdogFuture = watchdog.after(timeoutMillis, () -> finishWith(AiStreamEvent.error(
                    ErrorCode.AI_UNAVAILABLE.getCode(), "AI 响应超时", AiFallbackVo.FALLBACK_TEXT)));
        }

        /** 下发事件；已结束则丢弃。 */
        boolean send(AiStreamEvent event) {
            if (finished.get()) {
                return false;
            }
            emit(event);
            return true;
        }

        /** 失败收尾（只生效一次）。 */
        void fail(int code, String message, String fallbackText) {
            finishWith(AiStreamEvent.error(code, message, fallbackText));
        }

        /**
         * 正常收尾：把收尾事件与「标记结束」放进同一次 CAS。
         *
         * @param terminalEvent 收尾事件（如 {@code done}）；只有在本次调用赢得了 CAS 时才下发
         * @return 是否由本次调用完成收尾
         */
        boolean finishWith(AiStreamEvent terminalEvent) {
            if (!finished.compareAndSet(false, true)) {
                return false;
            }
            emit(terminalEvent);
            cancelWatchdog();
            completeQuietly();
            return true;
        }

        /** 置位结束标记并取消看门狗（不发任何事件）。 */
        private void markFinished() {
            if (finished.compareAndSet(false, true)) {
                cancelWatchdog();
                log.debug("[AI-STREAM] 连接已结束（客户端断开或容器回调），后续事件一律丢弃");
            }
        }

        private void emit(AiStreamEvent event) {
            try {
                // data 只放载荷：事件名已经在 SSE 的 event: 行里，
                // 再包一层 {"event":…,"payload":…} 等于让前端多看一层没人要的壳
                emitter.send(SseEmitter.event().name(event.event()).data(toJson(event.payload())));
            } catch (Exception e) {
                // 客户端断开或响应已提交：标记结束，不再尝试。
                // 这里可能运行在看门狗线程上，任何异常都不该逃出调度任务体，故 completeWithError 也包一层
                markFinished();
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                    // 已经关闭的连接再 completeWithError 会抛，忽略
                }
            }
        }

        private String toJson(Object payload) {
            try {
                return objectMapper.writeValueAsString(payload);
            } catch (Exception e) {
                // 事件不该拖垮连接：退化成一条最小可用载荷
                return "{\"note\":\"event serialize failed\"}";
            }
        }

        private void cancelWatchdog() {
            ScheduledFuture<?> future = watchdogFuture;
            if (future != null) {
                future.cancel(false);
            }
        }

        private void completeQuietly() {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 已提交或客户端已断开，无需再处理
            }
        }
    }
}
