package com.milktea.order.ai.decision;

/**
 * 决策提供者：把「菜单上下文 + 顾客原话」变成一个可执行的决策。
 *
 * <p>抽出接口的唯一目的是<b>让编排层可被独立测试</b>：{@code DecisionOrchestrator} 的路由规则
 * （置信度阈值、未知编码拒绝、草稿落库校验）是安全边界所在，必须能用桩替换真实模型来验证，
 * 否则「阈值以下不许下单」这类断言只能靠真实调用去撞。</p>
 *
 * <p>当前实现只有 {@link JevDecisionProvider} 一个（Jev 是可替换的 provider 这一设计已由接口
 * 承担：将来换任何决策后端，编排层与测试都不用改）。</p>
 */
public interface DecisionProvider {

    /**
     * 做出决策。
     *
     * @param state 决策上下文（含全量菜单、偏好与顾客原话）
     * @return 决策结果（含「不下单」这类正常结论）
     * @throws JevUnavailableException 决策链路不可用（认证/限流/过载/超时/解析失败）
     */
    DecisionResult decide(MenuState state);
}
