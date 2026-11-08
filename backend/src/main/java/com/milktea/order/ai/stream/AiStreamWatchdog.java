package com.milktea.order.ai.stream;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 流式编排的整体超时看门狗。
 *
 * <p><b>为什么必须有它</b>：决策分支串行包含「Jev 调用（含最多 3 次退避重试）」与
 * 「GLM 调用（含模型侧超时）」，两者叠加的最坏耗时远超 20 秒的体验预算。
 * 只依赖各自的超时是不够的——用户等的是一个「有结论的响应」，而不是「每一跳都没超时」。
 * 看门狗保证的是「无论中间哪一跳卡住，这一轮对话一定在预算内给出结论并关闭连接」。</p>
 *
 * <p><b>为什么单独一个调度器而不是用 {@link AiStreamExecutorConfig} 的池</b>：
 * 池线程是用来跑任务的，用它们去 sleep 等超时会把池占满（每轮对话再多吃一个线程，
 * 极端情况下形成「都在等超时、没人干活」）。而且这里需要的是 {@code ScheduledExecutorService}
 * 类型——若把它也注册成 Bean，容器里就会出现两个 {@code ExecutorService} 候选，
 * 注入方得靠 {@code @Qualifier} 消歧，而 Lombok 的 {@code @RequiredArgsConstructor}
 * 默认不复制字段上的 {@code @Qualifier}，那是个很容易踩的坑。让调度器「私有」最省事。</p>
 */
@Component
public class AiStreamWatchdog {

    /** 单线程足矣：任务体只是「标记 + complete」，不占时间。 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-stream-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 在指定延迟后执行任务。
     *
     * @param delayMillis 延迟毫秒数
     * @param task        超时处理（调用方须自行保证幂等：看门狗与正常路径可能同时到达）
     * @return 句柄，正常结束时应当 {@code cancel(false)}
     */
    public ScheduledFuture<?> after(long delayMillis, Runnable task) {
        return scheduler.schedule(task, Math.max(delayMillis, 1L), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
