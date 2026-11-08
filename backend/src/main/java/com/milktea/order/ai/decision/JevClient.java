package com.milktea.order.ai.decision;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Jev 决策端点客户端（{@code POST /v1/systemone}）：鉴权、退避重试、熔断降级。
 *
 * <h2>错误处理完全照官方建议实现</h2>
 * <ul>
 *   <li>{@code 401} 认证失败 / {@code 422} 校验失败 → <b>不重试</b>，直接失败；</li>
 *   <li>{@code 429} 限流 / {@code 529} 过载 / 网络超时 → 指数退避
 *       {@code 1s → 2s → 4s} 并加 jitter（官方原文建议）；</li>
 *   <li>连续失败达阈值 → 熔断打开，期间<b>不再发起网络请求</b>，冷却期满进入半开探测。</li>
 * </ul>
 *
 * <h2>为什么熔断必须"不发请求"而不是"发请求然后快速失败"</h2>
 * <p>官方限流是 {@code 100K tokens/秒 + 80 请求/秒} 且注明「可能不经通知调整」。
 * 在对方已经过载时继续打，只会把限流窗口越推越远；熔断的意义正是把流量从被保护方身上挪开。
 * 因此冷却期内是<b>本地短路</b>，连一次 TCP 都不发。</p>
 *
 * <h2>线程安全</h2>
 * <p>熔断状态是进程级的两个原子量（连续失败数、打开截止时刻），并发调用共享一份，
 * 与限流器一样属于「宁可误熔断一次，也不要打穿对方服务」的取舍。半开探测不做并发闸门：
 * 冷却期满后并发的若干个请求会同时成为探测请求，但它们最多打到「阈值次」就重新熔断，
 * 且探测本身是幂等的只读请求，代价可接受——换来的是实现上少一把锁与一个可能卡住的标志位。</p>
 */
@Slf4j
@Component
public class JevClient {

    /** 客户端错误：认证失败，不重试。 */
    private static final int STATUS_UNAUTHORIZED = 401;
    /** 客户端错误：请求体校验失败，不重试。 */
    private static final int STATUS_UNPROCESSABLE = 422;
    /** 限流。 */
    private static final int STATUS_RATE_LIMITED = 429;
    /** 过载。 */
    private static final int STATUS_OVERLOADED = 529;

    private final RestClient jevRestClient;
    private final JevProperties properties;
    private final ObjectMapper objectMapper;
    private final JevRuntime runtime;

    /** 连续失败计数（成功后归零）。 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** 熔断打开截止时刻（毫秒）；0 表示未熔断。 */
    private final AtomicLong openUntilMillis = new AtomicLong(0L);

    public JevClient(RestClient jevRestClient,
                     JevProperties properties,
                     ObjectMapper objectMapper,
                     JevRuntime runtime) {
        this.jevRestClient = jevRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.runtime = runtime;
    }

    /**
     * 发起一次决策请求（含退避与熔断）。
     *
     * @param request 决策请求
     * @return 决策响应
     * @throws JevUnavailableException 未启用/未配密钥、熔断中、退避耗尽或响应无法解析
     */
    public JevResponse ask(JevRequest request) {
        if (!properties.configured()) {
            throw new JevUnavailableException("jev 未启用或未配置密钥", true);
        }
        assertCircuitClosed();

        int attempts = properties.retryMaxAttemptsOrDefault();
        long baseMillis = properties.retryBaseMillisOrDefault();
        JevUnavailableException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                JevResponse response = doCall(request);
                onSuccess();
                return response;
            } catch (JevUnavailableException e) {
                last = e;
                if (e.isFatal() || attempt == attempts) {
                    break;
                }
                long backoff = backoffMillis(baseMillis, attempt);
                log.warn("[JEV] 第 {}/{} 次请求失败（{}），{}ms 后重试", attempt, attempts, e.getMessage(), backoff);
                try {
                    runtime.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        onFailure();
        throw last == null ? new JevUnavailableException("jev 请求失败") : last;
    }

    /** 当前是否处于熔断打开状态（供健康检查与测试断言）。 */
    public boolean circuitOpen() {
        long openUntil = openUntilMillis.get();
        return openUntil != 0L && runtime.nowMillis() < openUntil;
    }

    /**
     * 实际发一次 HTTP。
     *
     * <p>用 {@code exchange} 而不是 {@code retrieve().body(...)}：529 是非标准状态码，
     * 让框架去猜它属于客户端错还是服务端错不如自己按官方错误表分类，逻辑一目了然。</p>
     */
    private JevResponse doCall(JevRequest request) {
        try {
            return jevRestClient.post()
                    .uri(properties.pathOrDefault())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKeyOrEmpty())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((req, res) -> {
                        int status = res.getStatusCode().value();
                        String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (status >= 200 && status < 300) {
                            return parse(body);
                        }
                        throw classify(status);
                    });
        } catch (ResourceAccessException e) {
            // 连接/读取超时：外部依赖的瞬态故障，可重试
            throw new JevUnavailableException("jev 网络超时或不可达：" + e.getMessage());
        } catch (RestClientException e) {
            // TLS / DNS / 协议层等其它传输异常同样归入「不可用」：
            // 让它们穿透出去就不会计入熔断、也不会重试，等于把自己一直挂在一个已经坏掉的通道上
            throw new JevUnavailableException("jev 传输层异常：" + e.getMessage());
        }
    }

    /** 按官方错误表分类；fatal 表示不该重试。 */
    private JevUnavailableException classify(int status) {
        if (status == STATUS_UNAUTHORIZED || status == STATUS_UNPROCESSABLE) {
            return new JevUnavailableException("jev 拒绝请求 status=" + status + "（不重试）", true);
        }
        if (status == STATUS_RATE_LIMITED || status == STATUS_OVERLOADED) {
            return new JevUnavailableException("jev 限流或过载 status=" + status);
        }
        // 其余 4xx 属于请求本身的问题，重试无意义；5xx 视为瞬态可重试
        return new JevUnavailableException("jev 返回异常状态 status=" + status, status < 500);
    }

    private JevResponse parse(String body) {
        if (body == null || body.isBlank()) {
            // 2xx 却是空体：可能是响应被截断，按瞬态失败处理（可重试）
            throw new JevUnavailableException("jev 返回空响应体");
        }
        try {
            JevResponse response = objectMapper.readValue(body, JevResponse.class);
            if (response == null || response.answers() == null) {
                throw new JevUnavailableException("jev 响应缺少 answers 字段", true);
            }
            return response;
        } catch (JacksonException e) {
            throw new JevUnavailableException("jev 响应无法解析：" + e.getMessage(), true);
        }
    }

    /** 指数退避：base × 2^(attempt-1) + jitter（jitter 取 0..base/4，避免同时重试打齐）。 */
    private long backoffMillis(long baseMillis, int attempt) {
        long exponential = baseMillis * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(baseMillis / 4, 1));
        return exponential + jitter;
    }

    private void assertCircuitClosed() {
        long openUntil = openUntilMillis.get();
        if (openUntil == 0L) {
            return;
        }
        long now = runtime.nowMillis();
        if (now < openUntil) {
            throw new JevUnavailableException("jev 熔断打开中（剩余 " + ((openUntil - now) / 1000) + "s），本地短路降级");
        }
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        openUntilMillis.set(0L);
    }

    private void onFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        boolean wasOpen = openUntilMillis.get() != 0L;
        if (!wasOpen && failures < properties.circuitFailureThresholdOrDefault()) {
            return;
        }
        long openSeconds = properties.circuitOpenSecondsOrDefault();
        openUntilMillis.set(runtime.nowMillis() + openSeconds * 1000L);
        consecutiveFailures.set(0);
        log.warn("[JEV] 决策服务不可用（连续失败 {} 次{}），熔断 {}s 后进入半开探测",
                failures, wasOpen ? "，含半开探测失败" : "", openSeconds);
    }
}
