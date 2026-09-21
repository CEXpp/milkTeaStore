package com.milktea.order.ai.config;

import com.milktea.order.ai.OrderAssistant;
import com.milktea.order.ai.session.ChatMemoryStoreImpl;
import com.milktea.order.ai.tools.DraftOrderTool;
import com.milktea.order.ai.tools.MenuSearchTool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j 装配（T29，落实 LLD 6.1 / 6.2 / 6.5）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>三件套全配置化</b>：{@code ai.base-url / ai.api-key / ai.model} 全部走 {@code application-dev.yml}，
 *       换豆包等 OpenAI 兼容模型只改配置、代码零改动（LLD 6.2）。</li>
 *   <li><b>统一走 Spring 的 {@link ChatModel} 接口</b>：Bean 返回 {@code OpenAiChatModel}（其 implements
 *       {@code dev.langchain4j.model.chat.ChatModel}，已核验），AiServices 按接口注入，便于后续替换实现。</li>
 *   <li><b>不用 spring-boot4-starter</b>：该 starter 处于 beta 轨（1.20.0-beta30），且 LLD 6.2 明确要求手写 {@code @Bean}。</li>
 *   <li><b>apiKey 允许为空</b>：{@code OpenAiChatModel} 构建期不做 apiKey 校验（已核验 1.20.0 源码），
 *       缺失时应用照常启动、仅在实际请求时失败——满足 SRS 5.5「AI 不可用不影响手动点单主链路」。</li>
 * </ul>
 */
@Configuration
public class LangChain4jConfig {

    /**
     * 模型接入（LLD 6.2）：OpenAI 兼容协议指向 GLM。
     *
     * @param baseUrl        GLM 的 OpenAI 兼容端点
     * @param apiKey         密钥，环境变量注入（排期附录 A 指定 {@code AI_API_KEY}）
     * @param model          模型名，默认 glm-4-flash
     * @param temperature    采样温度，点单场景求稳定不求发散
     * @param timeoutSeconds 单次响应超时秒数（SRS 5.5：超过 10 秒视为超时）
     */
    @Bean
    public OpenAiChatModel chatModel(
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.api-key:}") String apiKey,
            @Value("${ai.model}") String model,
            @Value("${ai.temperature:0.2}") double temperature,
            @Value("${ai.timeout-seconds:10}") long timeoutSeconds) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    /**
     * 智能体装配（LLD 6.1 / 6.5）：每个 {@code session_uuid} 一份独立的滑动窗口记忆。
     *
     * <p>记忆窗口的读写落在自定义 {@link ChatMemoryStore} 上，消息持久化到 {@code ai_message} 表，
     * 因此重启应用后同一会话的历史仍在（排期 T29 卡完成标准「记忆持久化（重启不丢）」）。</p>
     *
     * <p>T30 在此追加注册 LLD 6.3 的四个 {@code @Tool}（{@link MenuSearchTool} 1 个 +
     * {@link DraftOrderTool} 3 个）。工具经 {@code @ToolMemoryId} 拿到本轮 {@code @MemoryId}
     * （即 {@code session_uuid}），从而把草稿写进正确的会话。</p>
     *
     * @param chatModel       见 {@link #chatModel}
     * @param memoryStore     自定义 ChatMemoryStore（映射 ai_message 表）
     * @param menuSearchTool  AI 工具：查菜单
     * @param draftOrderTool  AI 工具：改/看/清草稿
     * @param maxMessages     记忆窗口消息数，20 条 ≈ 10 轮问答（HLD「10 轮记忆」）
     */
    @Bean
    public OrderAssistant orderAssistant(
            ChatModel chatModel,
            ChatMemoryStoreImpl memoryStore,
            MenuSearchTool menuSearchTool,
            DraftOrderTool draftOrderTool,
            @Value("${ai.max-messages:20}") int maxMessages) {
        return AiServices.builder(OrderAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(maxMessages)
                        .chatMemoryStore(memoryStore)
                        .build())
                .tools(menuSearchTool, draftOrderTool)
                .build();
    }
}
