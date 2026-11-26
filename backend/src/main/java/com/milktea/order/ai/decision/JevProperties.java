package com.milktea.order.ai.decision;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jev 决策层配置（{@code jev.*}）。
 *
 * <p>TypeSafe Jev 是「System One」类型化决策模型，不是对话模型：请求只有
 * {@code model / state / questions} 三个字段，响应是带 {@code confidence} 的
 * {@code choice} / {@code score} / {@code noul} 答案。因此本配置项与 {@code ai.*}
 * （GLM 表述层）分离——两条链路可以各自开关、各自配超时，谁挂了都不牵连对方。</p>
 *
 * <p><b>为什么 model 默认钉住版本号而不是别名</b>：官方文档说明 {@code jev-latest}
 * 会随新版本上线而移动，而 confidence 阈值是针对某一具体版本调出来的。别名漂移会让
 * 「阈值该调多少」在一夜之间失效，故默认写死 {@code jev-1.13.0}，上别名需显式配置。</p>
 *
 * <p><b>为什么需要 {@link #configured()}</b>：{@code enabled=true} 但没配密钥时，
 * 每次对话都会白发一次 401 再降级——「关了」与「开着但没配全」对运维是两件不同的事，
 * 日志里必须能区分，故两者分别判定。</p>
 *
 * @param enabled                总开关（缺省关；与 ai 链路互不影响）
 * @param baseUrl                Jev 接入基址，默认官方 {@code https://api.typesafe.ai}
 * @param apiKey                 密钥，环境变量 {@code TYPESAFE_API_KEY} 注入，不入库
 * @param path                   决策端点路径，默认 {@code /v1/systemone}（官方唯一端点）
 * @param model                  模型或版本号（默认钉住 {@code jev-1.13.0}）
 * @param connectTimeoutSeconds  连接超时（秒）
 * @param timeoutSeconds         单次请求读超时（秒）；官方建议显式设整体超时
 * @param maxStateTokens         state 预算上限（估算口径），超出按订单序截断商品
 * @param confidenceThreshold    置信度阈值：达到才程序化落草稿，否则交 GLM 链路澄清
 * @param retryMaxAttempts       429/529/超时的最大尝试次数（含首次）
 * @param retryBaseMillis        退避基数（官方建议 1s→2s→4s 加 jitter）
 * @param circuitFailureThreshold 连续失败多少次后打开熔断
 * @param circuitOpenSeconds     熔断打开时长（秒），期满进入半开探测
 */
@ConfigurationProperties(prefix = "jev")
public record JevProperties(
        Boolean enabled,
        String baseUrl,
        String apiKey,
        String path,
        String model,
        Integer connectTimeoutSeconds,
        Integer timeoutSeconds,
        Integer maxStateTokens,
        Double confidenceThreshold,
        Integer retryMaxAttempts,
        Integer retryBaseMillis,
        Integer circuitFailureThreshold,
        Integer circuitOpenSeconds) {

    /** 官方接入基址。 */
    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";

    /** 官方唯一决策端点（所有 System One 模型共用，靠 body.model 路由）。 */
    public static final String DEFAULT_PATH = "/v1/systemone";

    /** 默认模型：钉住版本号而非 {@code jev-latest} 别名（见类注释）。 */
    public static final String DEFAULT_MODEL = "jev-1.13.0";

    /**
     * 默认置信度阈值。
     *
     * <p>取 0.72 的理由：低于它意味着「模型自己也不确定该选哪杯」，此时程序化落草稿
     * 等于替顾客拍板；而本方案的安全底线是「宁可多问一句，不可猜错下单」。</p>
     */
    public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.72;

    /**
     * state 预算下限保护：官方硬限为「state + 最长单问 ≤ 32k tokens」，
     * 此处默认 6000，留出充足余量给 questions 与未来菜单扩张。
     */
    public static final int DEFAULT_MAX_STATE_TOKENS = 6000;

    /** 总开关（缺省关）。 */
    public boolean enabledOrDefault() {
        return Boolean.TRUE.equals(enabled);
    }

    public String baseUrlOrDefault() {
        return (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
    }

    public String apiKeyOrEmpty() {
        return apiKey == null ? "" : apiKey.trim();
    }

    public String pathOrDefault() {
        return (path == null || path.isBlank()) ? DEFAULT_PATH : path.trim();
    }

    public String modelOrDefault() {
        return (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
    }

    public int connectTimeoutSecondsOrDefault() {
        return clamp(connectTimeoutSeconds, 3, 1, 30);
    }

    public int timeoutSecondsOrDefault() {
        return clamp(timeoutSeconds, 8, 1, 30);
    }

    public int maxStateTokensOrDefault() {
        return clamp(maxStateTokens, DEFAULT_MAX_STATE_TOKENS, 256, 30_000);
    }

    public double confidenceThresholdOrDefault() {
        if (confidenceThreshold == null) {
            return DEFAULT_CONFIDENCE_THRESHOLD;
        }
        return Math.min(Math.max(confidenceThreshold, 0.0), 1.0);
    }

    public int retryMaxAttemptsOrDefault() {
        return clamp(retryMaxAttempts, 3, 1, 5);
    }

    public int retryBaseMillisOrDefault() {
        return clamp(retryBaseMillis, 1000, 50, 5_000);
    }

    public int circuitFailureThresholdOrDefault() {
        return clamp(circuitFailureThreshold, 5, 1, 100);
    }

    public int circuitOpenSecondsOrDefault() {
        return clamp(circuitOpenSeconds, 60, 1, 3_600);
    }

    /** 是否「开着且配全了」：只有此时才发起真实请求，避免每次对话白发一次 401。 */
    public boolean configured() {
        return enabledOrDefault() && !apiKeyOrEmpty().isEmpty();
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        if (value == null) {
            return fallback;
        }
        return Math.min(Math.max(value, min), max);
    }
}
