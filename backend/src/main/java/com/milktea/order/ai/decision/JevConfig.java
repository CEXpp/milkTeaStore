package com.milktea.order.ai.decision;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Jev 决策层装配：启用 {@code jev.*} 绑定，并提供带超时的专用 HTTP 客户端。
 *
 * <p>沿用项目既有写法——不用 {@code @ConfigurationPropertiesScan}，而是逐域显式
 * {@code @EnableConfigurationProperties}，好处是「哪个域绑了哪些配置」在配置类上一眼可查
 * （与 {@code QueueConfig} / {@code DailyReportConfig} / {@code WxSubscribeConfig} 同一约定）。</p>
 *
 * <p><b>为什么单独一个客户端而不是复用全局 {@link RestClient.Builder}</b>：全局客户端用于本站
 * 业务接口；而调用 Jev 是<b>跨境外部依赖</b>，必须显式设连接与读超时，否则境外网络抖动会把
 * AI 点单整轮对话挂住。超时在这里是「用户体验」而非「技术细节」：宁可快速降级到 GLM，
 * 也不能让顾客盯着转圈。</p>
 *
 * <p><b>注意</b>：本 Bean 只负责「怎么发 HTTP」，重试、退避与熔断在 {@link JevClient} 内，
 * 因为那三条策略需要读响应状态码，属于业务语义。</p>
 */
@Configuration
@EnableConfigurationProperties(JevProperties.class)
public class JevConfig {

    /**
     * Jev 决策端点客户端。
     *
     * <p>baseUrl 与读写超时均来自配置；{@link RestClient} 本身不校验密钥，
     * 故密钥缺失时应用照常启动（与 {@code OpenAiChatModel} 的处理一致），
     * 只在真实请求时失败并降级。</p>
     */
    @Bean
    public RestClient jevRestClient(JevProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSecondsOrDefault()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(properties.timeoutSecondsOrDefault()));
        return RestClient.builder()
                .baseUrl(properties.baseUrlOrDefault())
                .requestFactory(factory)
                .build();
    }
}
