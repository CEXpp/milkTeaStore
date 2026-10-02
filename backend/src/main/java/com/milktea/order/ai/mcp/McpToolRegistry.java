package com.milktea.order.ai.mcp;

import com.milktea.order.ai.tools.DraftOrderTool;
import com.milktea.order.ai.tools.MenuSearchTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 参与 MCP 暴露的工具集合（T72）。
 *
 * <p>把两批工具收在一个 Bean 里，让「MCP 壳与本地助手共用同一份实现」这件事
 * 在代码结构上可核对：{@code OrderAssistant} 注入的与本 Bean 注入的是
 * <b>同一批 Spring 单例</b>，不存在「MCP 一套实现、本地另一套实现」的可能。</p>
 */
@Component
@RequiredArgsConstructor
public class McpToolRegistry {

    private final MenuSearchTool menuSearchTool;
    private final DraftOrderTool draftOrderTool;

    public MenuSearchTool menuSearchTool() {
        return menuSearchTool;
    }

    public DraftOrderTool draftOrderTool() {
        return draftOrderTool;
    }

    /**
     * 工具名 → 承载对象。
     *
     * <p>白名单式列举：<b>只有这里列出的工具可被 MCP 调用</b>。
     * 用 Map 而不是反射全扫，是为了杜绝「以后给某个工具类加个方法就意外对外暴露了」——
     * 对外暴露必须是显式动作。</p>
     */
    public java.util.Map<String, Object> targetsByToolName() {
        return java.util.Map.of(
                "searchMenu", menuSearchTool,
                "updateDraftOrder", draftOrderTool,
                "getDraftOrder", draftOrderTool,
                "clearDraftOrder", draftOrderTool);
    }
}