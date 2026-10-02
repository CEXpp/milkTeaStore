package com.milktea.order.ai.copilot;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 店长 Copilot 的工具调用轨迹（T67）。
 *
 * <p><b>用途</b>：让面板能展示「可折叠的原始数据表格供核对」——存的是模型实际读到的
 * 工具返回原文，因此验收项「所有回答中的数字能在统计接口中复现」是<b>结构上的必然</b>：
 * 面板显示的就是工具返回值本身，而工具又直接委托统计接口。</p>
 *
 * <p><b>为什么用 ScopedValue 而不是字段/ThreadLocal</b>：助手实例是单例、被多个店长
 * 并发共用，把轨迹存成实例字段会串号（A 店长看到 B 店长的查询）。ThreadLocal 能隔离，
 * 但项目约定（LLD 9.x / AuthContext）用 {@link ScopedValue}，此处与之一致。</p>
 *
 * <p><b>可变对象 + 不可变绑定</b>：{@code ScopedValue} 绑定后不能改，但它指向的
 * <b>对象</b>可以变——轨迹就是这个对象。回调往里追加、服务层调用结束后读取。</p>
 */
public final class CopilotTrace {

    /** 当前请求的轨迹；未绑定时表示「不在 Copilot 调用路径上」。 */
    public static final ScopedValue<CopilotTrace> CURRENT = ScopedValue.newInstance();

    private final List<ToolCall> calls = new CopyOnWriteArrayList<>();

    /**
     * 记录一次工具调用。
     *
     * @param tool   工具名（模型请求的名字）
     * @param args   入参原文
     * @param result 工具返回原文（面板直接展示它）
     * @param failed 是否执行失败
     * @param rejected 是否因越权/未登记而被拦截
     */
    public void record(String tool, String args, String result, boolean failed, boolean rejected) {
        calls.add(new ToolCall(tool, args, result, failed, rejected));
    }

    /** 本次调用中实际执行过的工具（按发生顺序）。 */
    public List<ToolCall> calls() {
        return List.copyOf(calls);
    }

    /** 是否有工具调用被拦截（越权或未登记）。 */
    public boolean hasRejected() {
        return calls.stream().anyMatch(ToolCall::rejected);
    }

    /**
     * 一次工具调用。
     *
     * @param tool     工具名
     * @param args     入参原文
     * @param result   返回原文
     * @param failed   执行失败
     * @param rejected 被拦截（未登记 / 写操作）
     */
    public record ToolCall(String tool, String args, String result, boolean failed, boolean rejected) {
    }
}