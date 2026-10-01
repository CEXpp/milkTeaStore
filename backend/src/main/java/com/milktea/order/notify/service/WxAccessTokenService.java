package com.milktea.order.notify.service;

import com.milktea.order.notify.config.WxSubscribeConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * 微信接口调用凭据（access_token）获取与缓存（T44）。
 *
 * <p><b>契约</b>（微信官方文档，非推测）：
 * {@code GET /cgi-bin/token?appid=&secret=&grant_type=client_credential}
 * → {@code {"access_token":"...","expires_in":7200}}；失败返回 {@code {errcode, errmsg}}。</p>
 *
 * <p><b>为什么必须缓存</b>：access_token 全局唯一且有频次限制，每次下发都去换会很快被限流；
 * 文档明确「有效期 7200 秒，开发者需要进行妥善保存」。这里在内存缓存并在到期前
 * {@link #REFRESH_SKEW_SECONDS} 秒主动续期，避免「刚好过期」的边界失败。</p>
 *
 * <p><b>不可用即短路</b>：未配置 {@code wx.secret}（练手期默认）时 {@link #get()} 返回
 * {@code null}，调用方据此跳过下发——这比抛异常更贴合 SRS 9.3 的降级要求。</p>
 */
@Slf4j
@Service
public class WxAccessTokenService {

    /** 到期前提前续期的安全窗口（秒）：避免「取出时还有效、用时已过期」。 */
    private static final long REFRESH_SKEW_SECONDS = 300L;

    /** access_token 请求路径。 */
    private static final String TOKEN_PATH = "/cgi-bin/token";

    private final RestClient wxRestClient;

    @Value("${wx.appid:}")
    private String appid;

    @Value("${wx.secret:}")
    private String secret;

    /** 缓存：volatile 保证读可见；写入在 synchronized 块内，避免并发重复换取。 */
    private volatile CachedToken cached;

    public WxAccessTokenService(@Qualifier("wxRestClient") RestClient wxRestClient) {
        this.wxRestClient = wxRestClient;
    }

    /**
     * 取当前可用的 access_token；配置缺失或换取失败时返回 {@code null}（调用方应跳过下发）。
     */
    public String get() {
        if (!StringUtils.hasText(appid) || !StringUtils.hasText(secret)) {
            // 练手期常态：未配置 AppSecret，订阅消息整体不生效（非错误）
            log.debug("[T44] 未配置 wx.secret，跳过 access_token 获取");
            return null;
        }
        CachedToken current = cached;
        if (current != null && current.usable()) {
            return current.token();
        }
        synchronized (this) {
            CachedToken latest = cached;
            if (latest != null && latest.usable()) {
                return latest.token();
            }
            return refresh();
        }
    }

    /** 强制刷新（下发失败若因 token 失效，可在此重试一次）。 */
    private String refresh() {
        try {
            TokenResp resp = wxRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(TOKEN_PATH)
                            .queryParam("appid", appid)
                            .queryParam("secret", secret)
                            .queryParam("grant_type", "client_credential")
                            .build())
                    .retrieve()
                    .body(TokenResp.class);

            if (resp == null || !StringUtils.hasText(resp.getAccess_token())) {
                log.warn("[T44] access_token 获取失败：响应为空或缺少 access_token（errcode={} errmsg={}）",
                        resp == null ? null : resp.getErrcode(), resp == null ? null : resp.getErrmsg());
                return null;
            }
            long expiresIn = resp.getExpires_in() == null ? 7200L : resp.getExpires_in();
            long expireAt = Instant.now().getEpochSecond() + expiresIn - REFRESH_SKEW_SECONDS;
            cached = new CachedToken(resp.getAccess_token(), expireAt);
            log.info("[T44] access_token 已刷新，有效期 {} 秒", expiresIn);
            return resp.getAccess_token();
        } catch (Exception e) {
            // 网络 / 微信侧异常：不抛出，交由调用方跳过本次下发
            log.warn("[T44] access_token 获取异常：{}", e.getMessage());
            return null;
        }
    }

    /** 带到期时刻的缓存项。 */
    private record CachedToken(String token, long expireAtEpochSecond) {
        boolean usable() {
            return token != null && Instant.now().getEpochSecond() < expireAtEpochSecond;
        }
    }

    /**
     * access_token 响应（字段名与微信 JSON 原样对齐，避免命名策略依赖；
     * 与 {@code WxAuthService.WxSessionResp} 同理，用 JavaBean 而非 record 以兼容下划线字段名）。
     */
    @Data
    public static class TokenResp {
        private String access_token;
        private Long expires_in;
        private Integer errcode;
        private String errmsg;
    }

    /** 供启动日志/排查使用的基址说明。 */
    public static String apiBase() {
        return WxSubscribeConfig.WX_API_BASE;
    }
}
