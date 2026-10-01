package com.milktea.order.order.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.event.OrderEventPublisher;
import com.milktea.order.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时关单调度（T15，LLD 4.3）：每 60 秒把超时未支付的订单条件更新为 CLOSED。
 *
 * <p><b>原子防并发</b>：单条 {@code UPDATE orders SET status=CLOSED, closed_at=now
 * WHERE status=PENDING_PAYMENT AND created_at < now - timeout}——判定与写入在同一条
 * SQL 内完成，无 select-then-update 竞态；顾客恰在关单瞬间支付时，两方各自的条件更新
 * 只有一个能命中（支付为 PENDING_PAYMENT→PAID，关单为 PENDING_PAYMENT→CLOSED），
 * 另一方影响行数为 0 自然失败。</p>
 *
 * <p>超时分钟数走配置 {@code order.payment-timeout-minutes}（与下单响应 expireAt、
 * 顾客端展示口径同源），关单数打 INFO 日志；CLOSED 单不计入营业额（SRS 6.5）。
 * 测试用短超时（如 1 分钟）验证后须还原配置。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutJob {

    private final OrderMapper orderMapper;
    private final OrderEventPublisher eventPublisher;

    /** 支付期限（分钟），与 T10 下单响应 expireAt 同源。 */
    @Value("${order.payment-timeout-minutes:15}")
    private int paymentTimeoutMinutes;

    /**
     * 关单调度：{@code fixedDelay} 60 秒（上一轮执行结束后计时的固定间隔，不叠加）。
     *
     * <p>T43 补充：为发布「六态之 CLOSED」事件，先取候选单 id（保证还能精确回捞本次被关闭的行，
     * 不依赖 {@code closed_at} 的时间精度），关单 UPDATE 本身与判定语义完全不变。</p>
     */
    @Scheduled(fixedDelay = 60_000)
    public void closeExpiredOrders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.minusMinutes(paymentTimeoutMinutes);

        List<Long> candidates = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                        .select(Order::getId)
                        .eq(Order::getStatus, OrderStatus.PENDING_PAYMENT.name())
                        .lt(Order::getCreatedAt, deadline))
                .stream().map(Order::getId).toList();
        if (candidates.isEmpty()) {
            return;
        }

        int closed = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getStatus, OrderStatus.PENDING_PAYMENT.name())
                .lt(Order::getCreatedAt, deadline)
                .set(Order::getStatus, OrderStatus.CLOSED.name())
                .set(Order::getClosedAt, now)
                .set(Order::getUpdatedAt, now));

        if (closed == 0) {
            return;
        }
        log.info("[T15] 超时关单 {} 笔（超时阈值 {} 分钟，deadline={}）", closed, paymentTimeoutMinutes, deadline);

        // 仅对「确实被本次关闭」的单发布 CLOSED（候选单若在关单前完成支付则状态不是 CLOSED，自然不发）
        orderMapper.selectList(new LambdaQueryWrapper<Order>()
                        .in(Order::getId, candidates)
                        .eq(Order::getStatus, OrderStatus.CLOSED.name()))
                .forEach(eventPublisher::publishStatusChanged);
    }
}
