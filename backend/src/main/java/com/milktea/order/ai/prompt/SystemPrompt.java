package com.milktea.order.ai.prompt;

/**
 * AI 点单员系统提示词（LLD 6.4）。
 *
 * <p><b>本任务（T29）只放占位文本</b>：排期 T29 卡「AiServices 组装 OrderAssistant 接口 +
 * {@code @SystemMessage} 提示词占位（T31 填全文）」；LLD 6.4 的提示词全文由 T31 落地，
 * 届时只替换本常量的值、不动 {@link com.milktea.order.ai.OrderAssistant} 的结构与签名。</p>
 *
 * <p>常量必须是编译期常量（{@code static final String} 字面量），因为注解值不支持运行时表达式。</p>
 */
public final class SystemPrompt {

    /**
     * 占位提示词：仅约束「只做点单、简短中文回复」，供 T29 最小连通与记忆验证；
     * T31 按 LLD 6.4 逐条替换为全文（角色 / 工具规则 / 约束 / 输出风格四段）。
     */
    public static final String SYSTEM_MESSAGE =
            "你是奶茶店「小茶」，一名热情简短的点单员。你的唯一职责是帮顾客点单，与点单无关的话题一句话礼貌拉回。"
                    + "回复一律中文，不超过两句话。";

    private SystemPrompt() {
    }
}
