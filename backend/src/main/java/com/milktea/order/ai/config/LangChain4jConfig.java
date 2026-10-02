package com.milktea.order.ai.config;

import com.milktea.order.ai.OrderAssistant;
import com.milktea.order.ai.ShopCopilotAssistant;
import com.milktea.order.ai.copilot.CopilotTrace;
import com.milktea.order.ai.session.ChatMemoryStoreImpl;
import com.milktea.order.ai.tools.AiToolWhitelist;
import com.milktea.order.ai.tools.DraftOrderTool;
import com.milktea.order.ai.tools.MenuSearchTool;
import com.milktea.order.ai.tools.ShopStatsTool;
import com.milktea.order.forecast.service.PrepAdviceNarrator;
import com.milktea.order.report.service.DailyReportNarrator;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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
     * 店长 Copilot 装配（T66/T67，W07）。
     *
     * <p><b>只注册只读统计工具</b>：{@link ShopStatsTool} 的四个 {@code @Tool} 全部是查询，
     * 没有任何写操作。越权提问（「帮我把珍珠奶茶下架」）之所以被拒，根因是
     * <b>模型手里根本没有这样的工具可调</b>，而不是靠提示词叮嘱它别做
     * （SRS 5.1 边界 + T66 设计纪律「只读是 AI 进入后台的正确权限级别」）。</p>
     *
     * <p><b>与点单助手完全隔离</b>：本实例不注册 {@link MenuSearchTool} 与
     * {@link DraftOrderTool}——那两个能改草稿，不该出现在经营参谋手里。两个
     * {@code AiServices} 各自持有工具集，互不影响。</p>
     *
     * <p><b>启动自检</b>：装配前先跑 {@link AiToolWhitelist#assertReadOnly()}，
     * 白名单里一旦混进写操作直接让应用起不来——带着一个能改数据的 AI 上线，
     * 比启动失败严重得多。</p>
     *
     * <p><b>越权拦截是两道</b>（T67）：第一道是「压根没注册写工具」；
     * 第二道是 {@code hallucinatedToolNameStrategy}——模型幻觉出一个写操作工具名时
     * 一律改写成拒绝语回给模型。提示词只负责让回答得体，**不承担安全职责**。</p>
     *
     * @param chatModel     见 {@link #chatModel}
     * @param statsTool     只读统计工具（唯一注入的工具）
     * @param maxMessages   记忆窗口消息数
     */
    @Bean
    public ShopCopilotAssistant shopCopilotAssistant(
            ChatModel chatModel,
            ShopStatsTool statsTool,
            @Value("${ai.max-messages:20}") int maxMessages) {
        AiToolWhitelist.assertReadOnly();
        return AiServices.builder(ShopCopilotAssistant.class)
                .chatModel(chatModel)
                // Copilot 是**真的**要多轮上下文（店长会追问「那上周呢」「哪个渠道贡献最大」），
                // 所以必须挂 ChatMemoryProvider——LangChain4j 在装配期就会校验：
                // 接口带 @MemoryId 而未配 provider，直接抛 IllegalConfigurationException。
                //
                // **刻意不注入 ChatMemoryStoreImpl**：那是点单会话的表，memoryId 会被当作
                // ai_session.uuid 去查，而 Copilot 的会话标识根本不在该表里——共用只会读到
                // 空历史 + 每次写回都被跳过（只 WARN），表现为「多轮追问时模型突然失忆」，
                // 且在日志里很难归因。此处用进程内窗口：Copilot 是店主当面对话，
                // 重启后丢历史可以接受，换来的是不污染点单记忆、不依赖额外表。
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(maxMessages)
                        .build())
                .tools(statsTool)
                // 拦截未登记的工具名（T67）：模型「幻觉」出一个写操作工具名时，
                // 缺省行为可能被当作可执行调用；这里一律改写成拒绝语回给模型，
                // 模型据此回答「我没有修改权限」。这是越权拦截的**最后一道**，
                // 第一道是「根本没注册写工具」。
                .hallucinatedToolNameStrategy(request -> ToolExecutionResultMessage.from(
                        request,
                        "工具 " + request.name() + " 不存在。本助手只有查询权限，没有任何修改数据的工具；"
                                + "请告知用户该操作需到对应管理页面手动完成。"))
                // 记录工具调用轨迹（T67）：面板的「原始数据」直接展示工具返回原文，
                // 因此「AI 的数字能在统计接口中复现」不靠人工比对，而是同源。
                .afterToolExecution(execution -> {
                    CopilotTrace trace = CopilotTrace.CURRENT.isBound() ? CopilotTrace.CURRENT.get() : null;
                    if (trace == null) {
                        return;
                    }
                    String name = execution.request() == null ? "unknown" : execution.request().name();
                    String args = execution.request() == null ? null : execution.request().arguments();
                    trace.record(name, args, execution.result(), execution.hasFailed(),
                            !AiToolWhitelist.isAllowed(name));
                })
                .build();
    }

    /**
     * 每日日报表述层装配（T68，W08）。
     *
     * <p><b>不注册任何工具</b>：指标与异常都已由 {@code DailyReportCalculator} 算完，
     * 模型只负责讲成人话。给它工具就等于允许它自己去查数、自由找规律——
     * 那正是「编造趋势」的来源（任务卡关键设计「AI 只做表述层，不做计算层」）。</p>
     */
    @Bean
    public DailyReportNarrator dailyReportNarrator(ChatModel chatModel) {
        return AiServices.builder(DailyReportNarrator.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * 备料建议表述层装配（T69，W09）。
     *
     * <p><b>不注册任何工具</b>：预测单量与销量结构已由 {@code DemandForecastService} 算完，
     * 模型只负责措辞。给它工具就等于允许它自己去查数、自由找规律。</p>
     */
    @Bean
    public PrepAdviceNarrator prepAdviceNarrator(ChatModel chatModel) {
        return AiServices.builder(PrepAdviceNarrator.class)
                .chatModel(chatModel)
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
