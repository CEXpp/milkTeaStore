package com.milktea.order.ai.decision;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jev 客户端单元测试：错误处理与熔断行为对齐官方错误表。
 *
 * <p><b>为什么用 JDK 内置的 {@code com.sun.net.httpserver.HttpServer} 起桩</b>：验证「429 退避」
 * 「529 熔断」这类策略必须能<b>控制响应状态码与调用次数</b>，而项目纪律是不为测试新增依赖
 * （没有 WireMock/MockWebServer）。JDK 自带的 HttpServer 足够，且启动成本近乎为零。</p>
 *
 * <p><b>为什么注入假 {@link JevRuntime}</b>：真实退避要等 1s→2s→4s、熔断冷却要等 60s。
 * 换成可控时钟后，这些断言是「确定性的、毫秒级的」，而不是「跑得慢还偶发超时」。</p>
 */
class JevClientTest {

    private static final String VALID_BODY = "{\"model\":\"jev-test\",\"answers\":{"
            + "\"product\":{\"type\":\"choice\",\"choice\":\"P01\",\"confidence\":0.9}},"
            + "\"usage\":{\"input_tokens\":120,\"output_tokens\":7}}";

    private HttpServer server;

    /** 桩收到的请求次数（熔断断言用的就是它「不再增长」）。 */
    private final AtomicInteger hits = new AtomicInteger();

    private final List<String> requestBodies = new ArrayList<>();

    private final List<String> authHeaders = new ArrayList<>();

    /** 按顺序返回的状态码；耗尽后一律返回 {@link #defaultStatus}。 */
    private final Deque<Integer> statusSequence = new ArrayDeque<>();

    private int defaultStatus = 200;

    private String responseBody = VALID_BODY;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("请求形状符合官方契约：Bearer 鉴权 + model/state/questions 三字段")
    void sendsContractShapedRequest() {
        JevClient client = client(props(3, 5, 60), new FakeRuntime());

        JevResponse response = client.ask(sampleRequest());

        assertEquals(1, hits.get(), "只应发起一次请求");
        assertEquals("Bearer test-key", authHeaders.get(0), "必须带 Bearer 鉴权头");
        String body = requestBodies.get(0);
        assertTrue(body.contains("\"model\":\"jev-1.13.0\""), "model 字段应钉住配置版本：" + body);
        assertTrue(body.contains("\"state\":\"menu state\""), "state 字段应原样下发");
        assertTrue(body.contains("\"questions\""), "questions 字段必须存在");
        assertEquals("P01", response.answer("product").choice());
        assertEquals(120, response.inputTokens());
        assertEquals(7, response.outputTokens());
    }

    @Test
    @DisplayName("401 认证失败：不重试，立即失败")
    void unauthorizedIsNotRetried() {
        statusSequence.add(401);
        JevClient client = client(props(3, 5, 60), new FakeRuntime());

        JevUnavailableException error = assertThrows(JevUnavailableException.class,
                () -> client.ask(sampleRequest()));

        assertEquals(1, hits.get(), "官方要求 401 不重试，必须只发一次");
        assertTrue(error.isFatal(), "401 应标记为不可重试");
    }

    @Test
    @DisplayName("422 校验失败：不重试")
    void unprocessableIsNotRetried() {
        statusSequence.add(422);
        JevClient client = client(props(3, 5, 60), new FakeRuntime());

        JevUnavailableException error = assertThrows(JevUnavailableException.class,
                () -> client.ask(sampleRequest()));

        assertEquals(1, hits.get());
        assertTrue(error.isFatal());
    }

    @Test
    @DisplayName("429 限流：按 1s 起退避后重试并成功")
    void rateLimitedIsRetriedWithBackoff() {
        statusSequence.add(429);
        FakeRuntime runtime = new FakeRuntime();
        JevClient client = client(props(3, 5, 60), runtime);

        JevResponse response = client.ask(sampleRequest());

        assertNotNull(response);
        assertEquals(2, hits.get(), "429 后应重试一次");
        assertEquals(1, runtime.sleeps.size(), "退避应等待一次");
        assertTrue(runtime.sleeps.get(0) >= 1000, "退避基数至少 1s（官方建议 1s→2s→4s）");
    }

    @Test
    @DisplayName("529 过载：达到阈值即熔断，熔断期内本地短路不发请求")
    void overloadTripsCircuitBreaker() {
        defaultStatus = 529;
        JevProperties properties = props(1, 1, 60);
        JevClient client = client(properties, new FakeRuntime());

        assertThrows(JevUnavailableException.class, () -> client.ask(sampleRequest()));
        assertEquals(1, hits.get());
        assertTrue(client.circuitOpen(), "连续失败达阈值后应处于熔断打开状态");

        // 熔断期内再调用：本地短路，桩的计数必须不增长
        assertThrows(JevUnavailableException.class, () -> client.ask(sampleRequest()));
        assertEquals(1, hits.get(), "熔断期内不得发起任何网络请求");
    }

    @Test
    @DisplayName("冷却期满：半开探测成功即恢复，失败则重新熔断")
    void halfOpenProbeRestoresOrReopens() {
        defaultStatus = 529;
        FakeRuntime runtime = new FakeRuntime();
        JevClient client = client(props(1, 1, 60), runtime);

        assertThrows(JevUnavailableException.class, () -> client.ask(sampleRequest()));
        assertTrue(client.circuitOpen());

        // 冷却期满 + 服务恢复 → 探测成功，熔断复位
        runtime.advance(61_000);
        defaultStatus = 200;
        assertNotNull(client.ask(sampleRequest()));
        assertEquals(2, hits.get());
        assertFalse(client.circuitOpen(), "半开探测成功后应恢复");

        // 再次失败 → 立即重新熔断（半开探测失败不重新计数）
        defaultStatus = 529;
        assertThrows(JevUnavailableException.class, () -> client.ask(sampleRequest()));
        assertTrue(client.circuitOpen(), "半开探测失败应重新熔断");
        int hitsAfterReopen = hits.get();
        assertThrows(JevUnavailableException.class, () -> client.ask(sampleRequest()));
        assertEquals(hitsAfterReopen, hits.get(), "重新熔断后同样不得发请求");
    }

    @Test
    @DisplayName("未配密钥：本地短路，不发请求也不抛网络异常")
    void missingApiKeyShortCircuits() {
        JevProperties properties = new JevProperties(true, "http://127.0.0.1:1", "", "/v1/systemone",
                "jev-1.13.0", 1, 1, 6000, 0.72, 3, 1000, 5, 60);
        JevClient client = client(properties, new FakeRuntime());

        JevUnavailableException error = assertThrows(JevUnavailableException.class,
                () -> client.ask(sampleRequest()));

        assertEquals(0, hits.get(), "未配密钥不应发起请求");
        assertTrue(error.isFatal());
    }

    @Test
    @DisplayName("响应无法解析：标记为不可重试，避免拿坏数据反复打对方")
    void unparsableResponseIsFatal() {
        responseBody = "not-a-json";
        JevClient client = client(props(3, 5, 60), new FakeRuntime());

        JevUnavailableException error = assertThrows(JevUnavailableException.class,
                () -> client.ask(sampleRequest()));

        assertEquals(1, hits.get());
        assertTrue(error.isFatal());
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));

        int status = statusSequence.isEmpty() ? defaultStatus : statusSequence.poll();
        String payload = (status >= 200 && status < 300) ? responseBody : "{}";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private JevClient client(JevProperties properties, JevRuntime runtime) {
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();
        return new JevClient(restClient, properties, JsonMapper.builder().build(), runtime);
    }

    private JevProperties props(Integer retryMaxAttempts, Integer circuitFailureThreshold, Integer circuitOpenSeconds) {
        return new JevProperties(true, "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", "/v1/systemone", "jev-1.13.0",
                2, 3, 6000, 0.72,
                retryMaxAttempts, 1000, circuitFailureThreshold, circuitOpenSeconds);
    }

    private JevRequest sampleRequest() {
        return new JevRequest("jev-1.13.0", "menu state",
                Map.of("product", JevRequest.Question.choice("pick one", Map.of("P01", "one"))));
    }

    /** 可控时钟与休眠记录器：把「等 1s / 等 60s」变成瞬时且可断言。 */
    private static final class FakeRuntime extends JevRuntime {

        private long now;

        private final List<Long> sleeps = new ArrayList<>();

        @Override
        public long nowMillis() {
            return now;
        }

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
            now += millis;
        }

        void advance(long millis) {
            now += millis;
        }
    }
}
