package com.milktea.order.ai.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按顾客维度的内存滑动窗口限流（T31，LLD 9.2「chat 接口按顾客维度简单限流」）。
 *
 * <p>目的：保护免费档模型的调用额度（HLD 风险 R2）。窗口内的请求时间戳保存在
 * {@link ConcurrentHashMap} 中，{@code compute} 保证同一顾客的「判断 + 记账」原子完成。</p>
 *
 * <p>为什么不用 {@code ScopedValue}：本类需要的是<b>跨请求共享</b>的计数状态，
 * 而 {@code ScopedValue} 表达的是「沿调用栈向下传递的请求上下文」，语义不同。
 * 项目约定「用 ScopedValue 替代 ThreadLocal」针对的是身份上下文（见
 * {@link com.milktea.order.common.jwt.AuthContext}），不适用于此类共享计数。</p>
 *
 * <p>内存有界性：顾客的窗口一旦清空即从 map 中移除；停用不再访问的顾客由
 * {@link #evictIdleWindows()} 定时兜底回收（复用 LLD 4.3 的调度模式）。</p>
 */
@Slf4j
@Component
public class CustomerRateLimiter {

    /** 滑动窗口长度：1 分钟（LLD 9.2「每分钟 10 次」） */
    private static final long WINDOW_MILLIS = 60_000L;

    /** 额度为 0 或负数时的兜底额度，避免误配把 AI 入口整个锁死 */
    private static final int DEFAULT_LIMIT = 10;

    private final int limitPerMinute;

    private final ConcurrentMap<Long, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * @param limitPerMinute 每顾客每分钟可用次数（{@code ai.rate-limit-per-minute}，默认 10）
     */
    public CustomerRateLimiter(@Value("${ai.rate-limit-per-minute:10}") int limitPerMinute) {
        this.limitPerMinute = limitPerMinute > 0 ? limitPerMinute : DEFAULT_LIMIT;
    }

    /**
     * 尝试获取一次调用额度。
     *
     * @param customerId 当前顾客主键；为 {@code null} 时无法归属，直接放行（身份缺失由鉴权层负责拦截）
     * @return {@code true} 表示本次放行并已记账；{@code false} 表示已超出窗口额度
     */
    public boolean tryAcquire(Long customerId) {
        if (customerId == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        AtomicBoolean allowed = new AtomicBoolean(true);
        windows.compute(customerId, (key, deque) -> {
            Deque<Long> window = deque == null ? new ArrayDeque<>() : deque;
            prune(window, now);
            if (window.size() >= limitPerMinute) {
                allowed.set(false);
            } else {
                window.addLast(now);
            }
            // 窗口已清空则移除条目，使 map 规模与「近 1 分钟活跃顾客数」同阶
            return window.isEmpty() ? null : window;
        });
        return allowed.get();
    }

    /**
     * 回收已完全滑出窗口的顾客条目（复用 LLD 4.3 调度模式）。
     *
     * <p>兜底作用：只访问过一次的顾客不会再被 {@link #tryAcquire} 触碰，其条目需由本任务清除。
     * 清理同样经 {@code computeIfPresent}，与 {@link #tryAcquire} 走同一把「按键串行」的锁，
     * 避免直接操作非线程安全的 {@link ArrayDeque}。</p>
     */
    @Scheduled(fixedDelay = 60_000)
    public void evictIdleWindows() {
        long now = System.currentTimeMillis();
        int before = windows.size();
        for (Long customerId : windows.keySet()) {
            windows.computeIfPresent(customerId, (key, window) -> {
                prune(window, now);
                return window.isEmpty() ? null : window;
            });
        }
        int evicted = before - windows.size();
        if (evicted > 0) {
            log.debug("[T31] 限流窗口回收 {} 个空闲顾客条目，当前保留 {}", evicted, windows.size());
        }
    }

    /** 每顾客每分钟额度（供日志与联调核对）。 */
    public int limitPerMinute() {
        return limitPerMinute;
    }

    /** 丢弃已滑出窗口的时间戳。 */
    private void prune(Deque<Long> window, long now) {
        while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
            window.pollFirst();
        }
    }
}
