package com.milktea.order.ai.decision;

import org.springframework.stereotype.Component;

/**
 * Jev 决策层的时间与休眠原语（可替换，供单测注入假实现）。
 *
 * <p><b>为什么要抽这一层</b>：{@link JevClient} 的退避策略需要「等待 1s→2s→4s」，
 * 熔断需要「冷却 60s 后半开」。若直接调 {@code Thread.sleep} 与
 * {@code System.currentTimeMillis()}，这两条策略就<b>无法在单元测试里验证</b>——
 * 一个测试要真等 7 秒、另一个要真等 60 秒，没人会跑。</p>
 *
 * <p>抽成独立 Bean 而不是给 {@link JevClient} 加第二构造器，是为了遵守本项目的
 * 装配纪律（见 {@code StartupWiringTest}：多构造器且无唯一 {@code @Autowired} 会让
 * Spring 退回寻找无参构造器并直接启动失败）。</p>
 */
@Component
public class JevRuntime {

    /** 当前毫秒时间戳（熔断冷却判定的基准）。 */
    public long nowMillis() {
        return System.currentTimeMillis();
    }

    /**
     * 休眠指定毫秒（退避等待）。
     *
     * @param millis 休眠时长
     * @throws InterruptedException 线程被中断时抛出，由调用方决定是否中止重试
     */
    public void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
