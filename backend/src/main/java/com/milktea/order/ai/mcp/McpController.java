package com.milktea.order.ai.mcp;

import com.milktea.order.common.jwt.AuthContext;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 协议壳（T72，D3d · W11）。
 *
 * <p>实现 MCP 客户端真正会用到的三个方法：{@code initialize} / {@code tools/list} /
 * {@code tools/call}（JSON-RPC 2.0 over HTTP）。<b>没有别的接口</b>——
 * MCP 壳是协议外壳，不是第二套业务入口。</p>
 *
 * <p><b>鉴权沿用顾客侧识别方式</b>（任务卡要求）：路径属 {@code /api/customer/**}，
 * 走既有的顾客 JWT 过滤器。<b>不新增一套 MCP 专用令牌</b>——两套令牌意味着两套
 * 吊销口径与两处泄露面。</p>
 *
 * <p><b>草稿会话的键</b>：MCP 没有「会话」概念，故用 {@code mcp:{customerId}:{session}}，
 * 其中 session 由客户端显式传（{@code Mcp-Session-Id} 头或 {@code sessionId} 参数）。
 * 带上 customerId 是必须的——否则两个顾客用同一个 session 名就会共用一份草稿。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/customer/mcp")
@RequiredArgsConstructor
public class McpController {

    /** JSON-RPC 2.0 协议版本。 */
    private static final String JSONRPC_VERSION = "2.0";
    /** 本次实现的 MCP 协议版本（协议演进快，写死一个明确值好过含糊其辞）。 */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final McpToolBridge toolBridge;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * MCP 单一入口（JSON-RPC 2.0）。
     *
     * <p>单端点而非按方法拆路径：MCP 规范就是把所有调用打到同一个 HTTP 端点上，
     * 用 {@code method} 区分。拆成多个 REST 接口虽然更「像本项目」，但那就成了一堆
     * 要单独记的私有协议，不再是 MCP。</p>
     */
    @PostMapping(value = "", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> rpc(@RequestBody Map<String, Object> request,
                                   @RequestHeader(value = "Mcp-Session-Id", required = false) String headerSession,
                                   @RequestParam(value = "sessionId", required = false) String paramSession) {
        Object id = request.get("id");
        String method = String.valueOf(request.getOrDefault("method", ""));
        Map<String, Object> params = asMap(request.get("params"));

        try {
            return switch (method) {
                case "initialize" -> ok(id, initializeResult());
                case "tools/list" -> ok(id, Map.of("tools", toolList()));
                case "tools/call" -> ok(id, callResult(params, headerSession, paramSession));
                // notifications/initialized 等通知没有 id，按规范不回响应
                case "notifications/initialized" -> Map.of();
                default -> error(id, -32601, "不支持的方法：" + method);
            };
        } catch (Exception e) {
            log.warn("[T72] MCP 调用失败 method={} cause={}", method, e.getMessage());
            return error(id, -32603, "内部错误：" + e.getMessage());
        }
    }

    /** {@code initialize}：报出协议版本与能力，供客户端协商。 */
    private Map<String, Object> initializeResult() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));
        return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", capabilities,
                "serverInfo", Map.of("name", "milk-tea-store", "version", "1.0.0"));
    }

    /** {@code tools/list}：工具名 + 描述 + 入参 JSON Schema（全部取自本地同一份工具）。 */
    private List<Map<String, Object>> toolList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ToolSpecification spec : toolBridge.list()) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", spec.name());
            tool.put("description", spec.description());
            tool.put("inputSchema", schemaOf(spec.parameters()));
            list.add(tool);
        }
        return list;
    }

    /**
     * {@code tools/call}：转发到本地同一份工具实现。
     *
     * <p>结果一律包成 MCP 规定的 {@code {content:[{type:"text",text}], isError}} 结构。
     * {@code isError} 忠实反映工具是否失败——编造商品被拒时，客户端应当看到这是失败，
     * 而不是把「未找到该商品」当成一份有效菜单。</p>
     */
    private Map<String, Object> callResult(Map<String, Object> params,
                                           String headerSession, String paramSession) {
        String name = String.valueOf(params.getOrDefault("name", ""));
        Map<String, Object> arguments = asMap(params.get("arguments"));
        String session = firstNonBlank(headerSession, paramSession, "default");

        if (!toolBridge.isRegistered(name)) {
            return content("未登记的工具：" + name, true);
        }
        // 记忆 id 带上 customerId：否则两个顾客用同一个 session 名会共用一份草稿
        Long customerId = AuthContext.get() == null ? null : AuthContext.get().getCustomerId();
        String memoryId = "mcp:" + customerId + ":" + session;

        String text = toolBridge.call(name, json.writeValueAsString(arguments), memoryId);
        return content(text, false);
    }

    private Map<String, Object> content(String text, boolean isError) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(block));
        result.put("isError", isError);
        return result;
    }

    /** LangChain4j 的 JsonObjectSchema → MCP 的 JSON Schema（字段名本就一致，直通即可）。 */
    private Object schemaOf(JsonObjectSchema schema) {
        return schema == null ? Map.of("type", "object", "properties", Map.of()) : schema;
    }

    private Map<String, Object> ok(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", JSONRPC_VERSION);
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    /** JSON-RPC 错误码：-32700 解析错 / -32600 非法请求 / -32601 方法不存在 / -32603 内部错。 */
    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", JSONRPC_VERSION);
        response.put("id", id);
        response.put("error", error);
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "default";
    }
}
