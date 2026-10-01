package com.milktea.order.order.event;

/**
 * 订单事件监听器扩展点（T44 引入，LLD 11.1「一个事件体 + 多出口」）。
 *
 * <p>SSE 通道（T43）解决「在线顾客 / 商家看板实时可见」，但<b>离线顾客</b>收不到——
 * 微信订阅消息（T44）正是这条离线信道。两者消费同一份事件体，避免各自判定状态导致口径分叉
 * （LLD 11.4：「两信道内容同源，不重复造数」）。</p>
 *
 * <p><b>时序</b>：由 {@link OrderEventPublisher#dispatch} 调用，因此天然继承「事务提交后」
 * 语义——监听器看到的状态一定是已落库的真值，不会因事务回滚而产生「推送了却查不到」。</p>
 *
 * <p><b>失败隔离</b>：监听器抛出的异常由发布器捕获并记日志，<b>不影响</b>业务主链路，
 * 也不影响其他监听器（SRS 9.3 降级：推送失败不影响全流程）。</p>
 *
 * <p><b>身份上下文</b>：监听器可能运行在业务线程（提交后回调）上，但<b>不应</b>依赖
 * {@link com.milktea.order.common.jwt.AuthContext}——事件体已携带 {@code orderId}，
 * 所需数据一律由监听器自行查库获得，从而不涉及 {@code ScopedValue} 的跨线程传递。</p>
 */
@FunctionalInterface
public interface OrderEventListener {

    /**
     * 处理一条订单事件。
     *
     * @param event 事件体（不可变 record，含 {@code type / orderId / status / pickupCode / ts}）
     */
    void onOrderEvent(OrderEvent event);
}
