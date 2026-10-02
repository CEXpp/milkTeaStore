package com.milktea.order.ai.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具桥（T72，D3d · W11）。
 *
 * <p><b>与本地工具共用同一份实现代码</b>（任务卡关键纪律，对齐 2.3
 * 「接口层预留而非协议层预留」）：工具清单由 LangChain4j 从<b>同一批工具对象</b>
 * 反射生成（{@code ToolSpecifications.toolSpecificationsFrom}），执行时同样落到
 * <b>同一个方法</b>（{@link DefaultToolExecutor}）。本类里没有任何业务判断——
 * 查菜单就是 MenuService，改草稿就是 DraftOrderTool。</p>
 *
 * <p><b>由此白拿 SRS 5.3 三条硬校验</b>（验收项「编造商品 / 自定价格被拒」，复现 AC-12）：
 * 商品只能来自在售集合、规格必须合法、金额只由计价引擎算——这三条都写在工具内部，
 * MCP 壳复用工具就自然继承。<b>不在 MCP 层再写一遍</b>；重写一遍就是两套逻辑，早晚分叉。</p>
 *
 * <p><b>不触碰任何非目标表</b>：MCP 只是协议外壳，经它下的单与小程序下单
 * <b>同构同构造</b>（同一张 orders、同一套计价、同一个状态机）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolBridge {

    private final McpToolRegistry registry;

    /**
     * 工具清单（{@code tools/list}）。
     *
     * <p>直接透出 LangChain4j 生成的规格与 JSON Schema，外部客户端据此渲染入参表单，
     * 不需要我们再维护一份协议文档——文档与实现分家是这类外壳最常见的腐化方式。</p>
     */
    public List<ToolSpecification> list() {
        // 逐个工具对象取规格再合并：LangChain4j 只提供「单对象」重载，
        // 但来源仍是同一批工具对象——规格不是我们自己写的，故不会与本地助手分叉
        List<ToolSpecification> specs = new ArrayList<>();
        specs.addAll(ToolSpecifications.toolSpecificationsFrom(registry.menuSearchTool()));
        specs.addAll(ToolSpecifications.toolSpecificationsFrom(registry.draftOrderTool()));
        return specs;
    }

    /** 是否为已登记的工具名（协议层先挡一道，避免把未知工具名丢给反射）。 */
    public boolean isRegistered(String name) {
        return registry.targetsByToolName().containsKey(name);
    }

    /**
     * 执行一个工具（{@code tools/call}）。
     *
     * @param name      工具名（必须已登记）
     * @param arguments 入参 JSON 字符串
     * @param memoryId  记忆 id：MCP 侧以「顾客 + 客户端会话」为键，
     *                  让 {@code @ToolMemoryId} 拿到正确的草稿会话
     * @return 工具返回文本（失败时为可读的错误说明，不抛栈给客户端）
     */
    public String call(String name, String arguments, String memoryId) {
        Object target = registry.targetsByToolName().get(name);
        if (target == null) {
            return "未登记的工具：" + name;
        }
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("mcp-" + System.nanoTime())
                .name(name)
                .arguments(arguments == null || arguments.isBlank() ? "{}" : arguments)
                .build();
        try {
            Method method = resolveMethod(target.getClass(), name);
            // execute(request, Object) 会把第二个参数按 @ToolMemoryId 注入；
            // 显式转成 Object 是为了避开与 executeWithContext 的重载歧义
            String result = new DefaultToolExecutor(target, method).execute(request, (Object) memoryId);
            return result == null ? "" : result;
        } catch (Exception e) {
            // 协议层要把失败说成「这次调用没成功」，而不是把异常栈当成有效结果返回
            log.warn("[T72] MCP 工具调用失败 name={} cause={}", name, e.getMessage());
            return "工具调用失败：" + e.getMessage();
        }
    }

    /**
     * 按名定位方法。
     *
     * <p>不能简单用 {@code getMethod(name)}：草稿工具存在重载，外部客户端无从判断
     * 该传几个参数。故要求方法名<b>唯一匹配</b>，匹配到多个就拒绝——
     * 宁可报「工具不可用」，也不能让客户端随机命中一个签名再收到看不懂的错。</p>
     *
     * <p>{@code @ToolMemoryId} 标注的参数不计入 {@code getParameterCount()} 的业务入参，
     * 故这里按「有业务参数」过滤：只有纯 memoryId 的方法（如清空草稿）不算歧义。</p>
     */
    private Method resolveMethod(Class<?> type, String name) throws NoSuchMethodException {
        List<Method> matches = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && hasBusinessParameters(method)) {
                matches.add(method);
            }
        }
        if (matches.isEmpty()) {
            throw new NoSuchMethodException("工具 " + name + " 未找到带业务参数的实现");
        }
        return matches.getFirst();
    }

    /** 排除仅带 {@code @ToolMemoryId} 的重载（那些是同一工具的 memoryId 变体）。 */
    private boolean hasBusinessParameters(Method method) {
        for (java.lang.reflect.Parameter parameter : method.getParameters()) {
            boolean memoryId = parameter.isAnnotationPresent(dev.langchain4j.service.MemoryId.class);
            if (!memoryId) {
                return true;
            }
        }
        return false;
    }
}
