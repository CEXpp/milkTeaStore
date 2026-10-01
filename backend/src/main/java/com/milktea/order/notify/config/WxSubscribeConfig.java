package com.milktea.order.notify.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 微信订阅消息装配（T44）：启用 {@code wx.subscribe.*} 绑定，并提供带超时的微信 HTTP 客户端。
 *
 * <p><b>为什么单独一个客户端而不是复用全局 {@link RestClient.Builder}</b>：全局客户端用于本站
 * 业务接口，无需超时；而向微信下发消息是<b>外部依赖</b>，必须显式设超时——否则微信侧抖动会让
 * 「出餐」这类商家操作被挂住（SRS 9.3 要求推送不影响主链路）。</p>
 */
@Configuration
@EnableConfigurationProperties(WxSubscribeProperties.class)
public class WxSubscribeConfig {

    /** 连接超时（秒）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** 单次请求超时（秒）。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** 微信开放接口基址。 */
    public static final String WX_API_BASE = "https://api.weixin.qq.com";

    @Bean
    public RestClient wxRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(REQUEST_TIMEOUT);
        return RestClient.builder()
                .baseUrl(WX_API_BASE)
                .requestFactory(factory)
                .build();
    }
}
