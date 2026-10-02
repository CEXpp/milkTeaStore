package com.milktea.order.ai.tools;

import java.util.List;
import java.util.Set;

/**
 * AI 工具白名单（T66，W07/W08）。
 *
 * <p><b>这份白名单是「AI 只能看、不能改」的单一事实来源</b>。它同时充当三件事：</p>
 * <ol>
 *   <li><b>声明</b>：{@link #READ_ONLY} 列出允许暴露给模型的所有工具名；</li>
 *   <li><b>校验依据</b>：{@link #isWriteLike(String)} 用命名特征识别写操作，
 *       供启动自检与测试断言「白名单里没有写操作」；</li>
 *   <li><b>拦截依据</b>：任何不在 {@link #READ_ONLY} 中的工具名都会被拒
 *       （见 {@code ShopCopilotService} 的越权拦截）。</li>
 * </ol>
 *
 * <p><b>为什么用「名称特征」而不是逐个列举写操作</b>：列举法只能挡住已知的写操作，
 * 将来有人加了 {@code deleteProduct} 却忘了更新名单，它就会静默地被暴露出去。
 * 反过来识别「名字像写操作」则默认拒绝新出现的东西——白名单的失败方向应该是
 * 「多拦一个」而不是「漏放一个」。</p>
 *
 * <p><b>这份白名单是编译期常量</b>，与 {@code ShopStatsTool} 上的 {@code @Tool} 方法一一对应；
 * 新增只读工具须同时更新此处，否则启动自检会失败——这是刻意的摩擦，
 * 避免「工具悄悄多了一个」而无人察觉。</p>
 */
public final class AiToolWhitelist {

    /**
     * 允许暴露给 AI 的工具名（只读）。
     *
     * <p>命名与 {@code ShopStatsTool} 的方法名一致——LangChain4j 默认以方法名作为工具名。</p>
     */
    public static final List<String> READ_ONLY = List.of(
            "dailySummary",
            "trend",
            "ranking",
            "orderFlow"
    );

    /**
     * 写操作命名特征。命中任一即视为「写操作」，一律不得进入白名单。
     *
     * <p>取自任务卡明确点名的四类：下架商品、改价、暂停接单、修改订单——
     * 以及它们的常见同义写法，宁可多拦。</p>
     */
    private static final List<String> WRITE_HINTS = List.of(
            // 下架 / 上架 / 删除
            "offShelf", "onShelf", "offline", "online", "delete", "remove", "disable", "enable",
            // 改价 / 改配置
            "update", "edit", "modify", "change", "set", "price", "adjust", "config",
            // 暂停 / 恢复接单
            "pause", "resume", "suspend", "open", "close",
            // 修改订单
            "void", "refund", "cancel", "confirm", "pay", "complete", "start", "create", "insert",
            // 入库出库（非目标表，但一并拦下）
            "stock", "inventory", "restock"
    );

    private AiToolWhitelist() {
    }

    /** 该工具名是否在白名单内（只读且已登记）。 */
    public static boolean isAllowed(String toolName) {
        return toolName != null && READ_ONLY.contains(toolName);
    }

    /** 该工具名是否「像写操作」（用于自检与测试断言）。 */
    public static boolean isWriteLike(String toolName) {
        if (toolName == null) {
            return false;
        }
        String lower = toolName.toLowerCase();
        for (String hint : WRITE_HINTS) {
            if (lower.contains(hint.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 启动自检：白名单自身不得包含任何写操作。
     *
     * <p>放在这里而不是写进注释，是因为它必须能<b>失败</b>：写操作一旦混进白名单，
     * 应用应当直接起不来，而不是带着一个能改数据的 AI 上线。</p>
     *
     * @throws IllegalStateException 白名单中存在写操作
     */
    public static void assertReadOnly() {
        for (String name : READ_ONLY) {
            if (isWriteLike(name)) {
                throw new IllegalStateException(
                        "AI 工具白名单不得包含写操作，但发现：" + name);
            }
        }
    }

    /** 白名单的只读副本（防止调用方改动）。 */
    public static Set<String> readOnlyNames() {
        return Set.copyOf(READ_ONLY);
    }
}