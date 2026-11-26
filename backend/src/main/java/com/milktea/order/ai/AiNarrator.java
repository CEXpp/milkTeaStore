package com.milktea.order.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 旁白层的阻塞式封装：把 LangChain4j 的 {@link dev.langchain4j.service.TokenStream}
 * 收敛成一个「逐段回调」的方法。
 *
 * <p><b>为什么要这一层</b>：编排层需要一个能在<b>独立线程里阻塞跑完</b>的旁白动作
 * （它与决策分支并发）。直接把 {@code TokenStream} 暴露给编排层，会让编排层被迫处理
 * 四个回调的装配细节，且单测里根本无法构造 {@code TokenStream}（依赖 LangChain4j 内部实现）。
 * 收敛成「输入原话 + 输出增量回调」后，编排层只依赖这一个方法，测试也能用桩替换。</p>
 *
 * <p><b>失败一律吞掉</b>：旁白只是「让顾客立刻有反馈」的锦上添花，它挂了绝不能影响
 * 决策与草稿卡——那是实打实的功能。故这里只记 WARN，不向上抛。</p>
 *
 * <p><b>线程</b>：{@link #narrate} 是阻塞的，必须由调用方放在独立线程执行
 * （见 {@code AiStreamOrchestrator} 的线程池）。本类不读取 {@code AuthContext}，
 * 因此跨线程调用不涉及身份上下文传递。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiNarrator {

    private final ChatNarrator chatNarrator;

    /**
     * 阻塞式生成承接话术，逐段回调增量文本。
     *
     * @param userMessage 顾客原话
     * @param onDelta     增量文本回调（可能被回调多次；空串会被过滤掉）
     */
    public void narrate(String userMessage, Consumer<String> onDelta) {
        try {
            // LangChain4j 1.20 的增量回调名为 onPartialResponse（旧版的 onNext 已移除）
            chatNarrator.narrate(userMessage)
                    .onPartialResponse(delta -> {
                        if (delta != null && !delta.isEmpty()) {
                            onDelta.accept(delta);
                        }
                    })
                    .onError(error -> log.warn("[AI] 旁白流中断（不影响主链路）：{}", messageOf(error)))
                    .start();
        } catch (Exception e) {
            // 模型未配置、网络不可达、超时……统统只是「这轮没有旁白」
            log.warn("[AI] 旁白流启动失败（不影响主链路）：{}", messageOf(e));
        }
    }

    private String messageOf(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
