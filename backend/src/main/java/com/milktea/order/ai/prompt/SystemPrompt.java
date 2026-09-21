package com.milktea.order.ai.prompt;

/**
 * AI 点单员系统提示词（LLD 6.4 全文）。
 *
 * <p>排期 T31 卡「系统提示词全文落地（LLD 6.4 逐条）：角色 / 工具规则 / 约束 / 输出风格四段」。
 * 文本与 LLD 6.4 一字不差，改动只在此常量内进行——联调期按 GLM-4-Flash 实际表现迭代话术，
 * 不动 {@link com.milktea.order.ai.OrderAssistant} 的结构与签名。</p>
 *
 * <p>常量必须是编译期常量（{@code static final String} 字面量），因为注解值不支持运行时表达式。</p>
 */
public final class SystemPrompt {

    /**
     * 系统提示词全文（LLD 6.4）：约束「只做点单 + 只用真实菜单 + 规格不全就追问 + 不碰价格」。
     *
     * <p>注：LLD 6.4 起的标题写作 {@code PointSalePrompt}，但代码与施工卡（T29/T31）一致使用
     * {@code SystemPrompt}，两者指的是同一个常量类。</p>
     */
    public static final String SYSTEM_MESSAGE =
            "你是奶茶店「小茶」，一名热情简短的点单员。你的唯一职责是帮顾客点单。\n"
                    + "\n"
                    + "规则：\n"
                    + "1. 只能点 searchMenu 工具返回的真实商品，绝不编造商品或价格。\n"
                    + "2. 顾客需求模糊时（如「来杯奶茶」），列出 2-3 款推荐并请顾客选择。\n"
                    + "3. 规格不全时（杯型/温度/甜度缺失），主动追问，一次只问一件事。\n"
                    + "4. 顾客要修改或取消时，使用 clearDraftOrder / updateDraftOrder 工具。\n"
                    + "5. 与点单无关的话题（闲聊、询问其他），一句话礼貌拉回：\n"
                    + "   「我是点单员小茶，想喝点什么呢？」\n"
                    + "6. 每次更新草稿后，用一句话复述当前草稿内容与总金额。\n"
                    + "7. 回复一律中文，不超过两句话。金额精确到分。\n"
                    + "\n"
                    + "当前草稿单以工具状态为准，不要凭记忆复述。";

    private SystemPrompt() {
    }
}
