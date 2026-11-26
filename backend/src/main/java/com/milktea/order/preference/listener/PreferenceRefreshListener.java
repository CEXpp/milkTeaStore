package com.milktea.order.preference.listener;

import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.event.OrderEvent;
import com.milktea.order.order.event.OrderEventListener;
import com.milktea.order.order.event.OrderEventType;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.preference.service.PreferenceService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 订单事件 → 口味偏好刷新（把「记忆」从对话热路径里挪出去）。
 *
 * <h2>触发口径</h2>
 * <ul>
 *   <li>{@code ORDER_STATUS_CHANGED} 且状态为 {@code PAID} → 刷新（这一单开始计入偏好）；</li>
 *   <li>{@code ORDER_STATUS_CHANGED} 且状态为 {@code COMPLETED} → 再刷一次
 *       （取餐窗口可能跨过时间边界，重算是幂等的，代价是一次索引扫描）；</li>
 *   <li>{@code PICKUP_READY}、{@code PREPARING}、{@code CLOSED}、{@code VOIDED} 等一律不触发
 *       ——未付款与已作废的单不计入偏好。</li>
 * </ul>
 *
 * <h2>为什么异步</h2>
 * <p>监听器运行在订单事务的 {@code afterCommit} 回调（业务线程）里。同步聚合会把
 * 「顾客点了立即支付」的响应时间绑到两张表的扫描上；而偏好晚几秒生效没有任何业务影响。
 * 用单线程执行器既立即返回，又天然串行化（同一顾客的并发放大会互相覆盖，
 * 虽然 upsert 本身幂等，但串行能省掉无谓的重复计算）。</p>
 *
 * <p><b>身份上下文</b>：本执行器不读取 {@code AuthContext}——事件体已带 {@code orderId}，
 * customerId 由本监听器查库获得（柜台单 customerId 为 null，直接跳过）。</p>
 *
 * <h2>降级</h2>
 * <p>聚合失败一律吞掉并记日志（{@link OrderEventListener} 的约定：监听器异常不影响主链路）。
 * 偏好刷新失败最坏的后果是「这次没有默认偏好」，而不是「支付失败」。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreferenceRefreshListener implements OrderEventListener {

    private final PreferenceService preferenceService;
    private final OrderMapper orderMapper;

    /** 单线程刷新执行器：串行化聚合并避免占用业务线程。 */
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "preference-refresh");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void onOrderEvent(OrderEvent event) {
        if (!isSettledTransition(event)) {
            return;
        }
        Long orderId = event.orderId();
        // 查库放进异步任务体：监听器运行在订单事务的 afterCommit 回调（业务线程）上，
        // 在那里同步查一次库等于把「支付」的响应时间绑上一次无谓的查询
        submit(() -> safeRebuild(orderId));
    }

    /**
     * 提交异步任务。
     *
     * <p>刻意留成可覆盖的方法：单测需要「同步执行 + 断言被调用」，而生产需要异步。
     * 这比把线程池做成构造器参数更轻——后者会让本类的构造器变成两个，
     * 触犯项目的装配纪律（见 {@code StartupWiringTest}：多构造器且无唯一 {@code @Autowired} 会让启动失败）。</p>
     */
    void submit(Runnable task) {
        refreshExecutor.execute(task);
    }

    @PreDestroy
    void shutdown() {
        refreshExecutor.shutdown();
    }

    private boolean isSettledTransition(OrderEvent event) {
        if (event == null || !OrderEventType.ORDER_STATUS_CHANGED.name().equals(event.type())) {
            return false;
        }
        return OrderStatus.PAID.name().equals(event.status())
                || OrderStatus.COMPLETED.name().equals(event.status());
    }

    private void safeRebuild(Long orderId) {
        try {
            Order order = orderId == null ? null : orderMapper.selectById(orderId);
            Long customerId = order == null ? null : order.getCustomerId();
            if (customerId == null) {
                // 订单已不存在，或是柜台单（没有归属顾客）：没有画像可言
                return;
            }
            preferenceService.rebuild(customerId);
        } catch (Exception e) {
            // SRS 9.3 口径：旁路能力失败不影响主链路；最坏就是这次没有默认偏好
            log.warn("[PREF] 订单事件触发的偏好刷新失败 orderId={}：{}", orderId, e.getMessage());
        }
    }
}
