package com.milktea.order.ai.decision;

/**
 * Jev 决策不可用（认证失败 / 校验失败 / 退避耗尽 / 熔断打开 / 响应无法解析）。
 *
 * <p><b>这是一条「预期内」的异常，不是故障</b>：调用方（{@link DecisionOrchestrator}）
 * 捕获它并降级到既有 GLM 对话链路，对顾客完全无感。之所以用异常而非返回 {@code null}，
 * 是因为 {@code null} 在决策语义里也能表示「置信度不够、不该落草稿」——两者必须可区分。</p>
 *
 * <p>消息只承载技术原因，不含用户原文与个人信息（日志脱敏纪律）。</p>
 */
public class JevUnavailableException extends RuntimeException {

    /** 是否属于「本就不该重试」的失败（401 认证 / 422 校验）。 */
    private final boolean fatal;

    public JevUnavailableException(String message) {
        this(message, false);
    }

    public JevUnavailableException(String message, boolean fatal) {
        super(message);
        this.fatal = fatal;
    }

    public boolean isFatal() {
        return fatal;
    }
}
