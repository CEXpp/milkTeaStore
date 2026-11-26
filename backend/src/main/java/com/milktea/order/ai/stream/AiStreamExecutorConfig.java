package com.milktea.order.ai.stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式编排的专用线程池（避免占用 Tomcat 线程）。
 *
 * <p><b>为什么必须独立线程池</b>：SSE 请求的处理线程要立刻把 {@code SseEmitter} 交还给容器，
 * 后续的模型调用与事件下发全部在池里跑。若直接在当前线程里同步跑完整轮对话，
 * 一个慢请求就会占着一个 Tomcat 工作线程直到超时——高峰时会把整个应用的入口拖住。</p>
 *
 * <p><b>为什么池子要给足</b>：一轮对话会<b>同时占用两个池线程</b>（旁白分支 + 决策分支并行）。
 * 因此池大小是「同时在线对话轮数」的两倍。默认 8 即约 4 轮并发；单店场景配合按顾客限流
 * （每分钟 10 次）完全够用，且这个值可通过 {@code ai.stream.pool-size} 调整。</p>
 *
 * <p><b>为什么用固定大小而不是虚拟线程</b>：本项目 Java 25 可用的虚拟线程确实适合阻塞型 IO，
 * 但这里还需要「有界」这个性质——虚拟线程无界，遇到模型侧整体变慢时会积压无数并发请求，
 * 把对方限流彻底打穿。有界池 + 队列满时拒绝，是更符合「保护外部依赖」的选择。</p>
 */
@Configuration
public class AiStreamExecutorConfig {

    /** 池大小边界：至少 2（一轮对话需要两个线程），至多 64（防止误配把机器打爆）。 */
    private static final int MIN_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 64;

    /** 队列容量边界：一轮对话可能等满整体超时，队列过长只会让请求排到「超时之后」才被处理。 */
    private static final int MIN_QUEUE_CAPACITY = 4;
    private static final int MAX_QUEUE_CAPACITY = 256;

    /**
     * 流式编排线程池。
     *
     * <p><b>必须自己 new {@link ThreadPoolExecutor} 而不是 {@code Executors.newFixedThreadPool}</b>：
     * 后者用的是<b>无界</b> {@code LinkedBlockingQueue}，那样「队列满即拒绝」的分支永远不会触发，
     * 请求会静默堆积到连接超时——与「保护外部依赖、宁可明确拒绝」的设计目标正好相反。
     * 有界队列 + {@code AbortPolicy} 才是注释所声称的语义。</p>
     *
     * <p>{@code destroyMethod = "shutdown"}：容器关闭时优雅停止，不再接受新任务。
     * 线程设为 daemon，避免残留线程拖住 JVM 退出。</p>
     *
     * @param poolSize      池大小（{@code ai.stream.pool-size}，默认 8）
     * @param queueCapacity 等待队列容量（{@code ai.stream.queue-capacity}，默认 32）
     */
    @Bean(name = "aiStreamExecutor", destroyMethod = "shutdown")
    public ExecutorService aiStreamExecutor(@Value("${ai.stream.pool-size:8}") int poolSize,
                                           @Value("${ai.stream.queue-capacity:32}") int queueCapacity) {
        int size = Math.min(Math.max(poolSize, MIN_POOL_SIZE), MAX_POOL_SIZE);
        int capacity = Math.min(Math.max(queueCapacity, MIN_QUEUE_CAPACITY), MAX_QUEUE_CAPACITY);
        AtomicInteger index = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "ai-stream-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }
}
