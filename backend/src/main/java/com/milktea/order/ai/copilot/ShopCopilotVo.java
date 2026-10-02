package com.milktea.order.ai.copilot;

import java.util.List;

/**
 * 店长 Copilot 一轮问答响应（T67，W07）。
 *
 * <p><b>结论 + 原始数据两段式</b>：{@code conclusion} 是模型讲的中文人话，
 * {@code tables} 是模型实际读到的工具返回原文。之所以两样都给，
 * 是因为店长需要能核对——「AI 说的数字」与「报表的数字」若不一致，一眼就能看出来。
 * 收起来的默认态是表格，展开才看细节。</p>
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param sessionId  会话标识：前端须回传以延续多轮上下文。
 *                   <b>由服务端下发而非前端自造</b>——前端编一个 id 与模型记忆窗口对不上，
 *                   多轮追问就会退化成互相独立的单轮问答
 * @param conclusion 结论（口语化中文；模型不可用时为空）
 * @param degraded   是否降级：模型不可用时为 true，此时只回原始数据不编结论
 * @param tip        降级或拒绝时的说明文案（如「AI 暂不可用，以下为原始数据」）
 * @param rejected   本轮是否出现越权拦截（前端据此把提示条染成警示色）
 * @param tables     工具返回的原始数据表格
 */
public record ShopCopilotVo(
        String sessionId,
        String conclusion,
        boolean degraded,
        String tip,
        boolean rejected,
        List<ToolResult> tables) {

    /**
     * 一次工具查询的原始结果。
     *
     * @param tool   工具名（如 dailySummary）
     * @param title  中文标题（如「当日经营概览」），供折叠面板展示
     * @param args   入参原文（如 {"date":"2026-10-02"}），让店长知道查的是哪一天
     * @param data   工具返回原文——**不是模型复述**，因此数字必然可在报表复现
     */
    public record ToolResult(String tool, String title, String args, String data) {
    }
}