package com.milktea.order.order.event;

import com.milktea.order.order.entity.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 实时事件发布器（T43，LLD 11.1）：SSE 通道注册表 + 订单事件投递。
 *
 * <p><b>通道维度</b>（LLD 11.1「连接维度」）：
 * 顾客按 customerId 单连接（同一顾客多端时<b>后连顶替前连</b>，旧连接被 complete），
 * 且订阅时绑定一个 orderId——只下发该单的事件；商家按 shop 维度单连接（本项目单店，
 * 故全局一条，同样后连顶替前连）。</p>
 *
 * <p><b>补齐状态</b>：顾客连接建立后立即补发一条<b>当前状态快照</b>（同为 ORDER_STATUS_CHANGED），
 * 断线重连后前端无需额外查询即已对齐服务端真值；商家连接不推快照（前端在 onopen 时刷新一次
 * {@code GET /orders/board} 拿到全量看板，避免把整板数据塞进事件体）。</p>
 *
 * <p><b>提交后投递</b>：业务在事务内发布时经 {@link TransactionSynchronization} 延迟到
 * {@code afterCommit}——否则客户端收到事件后立刻回查，可能仍读到提交前的旧值。</p>
 *
 * <p><b>连接保活</b>：每 15s 发送 {@code : ping} 注释行（LLD 11.1）。发送失败即判定断线，
 * 交由 {@code completeWithError} 触发清理。连接本身不设超时：{@code new SseEmitter(0L)} 经
 * {@code DeferredResult → AsyncContext.setTimeout(0)} 落到 Tomcat，而 Tomcat
 * {@code AbstractProcessor} 仅在 {@code asyncTimeout > 0} 时判定异步超时，故 0 即「不超时」，
 * 生命周期由心跳与客户端重连管理。</p>
 *
 * <p><b>线程与身份</b>：投递可能发生在业务线程（提交后回调）或调度线程（心跳）；
 * 两者都不读取 {@link com.milktea.order.common.jwt.AuthContext}——顾客身份在订阅时已解析为
 * customerId 存入注册表，无需跨线程传递 ScopedValue。SseEmitter 的 send 内部持写锁，多线程
 * 并发下发安全。</p>
 *
 * <p><b>非 SSE 出口（T44）</b>：除 SSE 通道外，每条事件还会回调全部 {@link OrderEventListener}
 * （如微信订阅消息，服务<b>离线顾客</b>）。这是「一个事件体 + 多出口」而非第二套事件——
 * 两个出口消费同一份 {@link OrderEvent}，口径天然一致（LLD 11.4「两信道内容同源」）。
 * 监听器异常被逐个隔离，且不阻塞 SSE 下发。</p>
 */
@Slf4j
@Component
public class OrderEventPublisher {

    /** 心跳间隔（LLD 11.1：每 15s 发送 `: ping` 注释行）。 */
    private static final long HEARTBEAT_MILLIS = 15_000L;

    private final ObjectMapper objectMapper;

    /**
     * 订单事件的非 SSE 出口（T44 微信订阅消息等）。
     *
     * <p>用 {@link ObjectProvider} 而非 {@code List}：无实现 bean 时（如订阅消息整体未启用、
     * 装配被裁剪）也<b>不应</b>让发布器启动失败——SSE 是主通道，不能被旁路出口拖垮。</p>
     */
    private final ObjectProvider<OrderEventListener> eventListeners;

    /** 顾客维度单连接：key = customerId。 */
    private final Map<Long, CustomerChannel> customerChannels = new ConcurrentHashMap<>();

    /** 商家维度单连接（单店）。 */
    private final AtomicReference<SseEmitter> adminChannel = new AtomicReference<>();

    public OrderEventPublisher(ObjectMapper objectMapper, ObjectProvider<OrderEventListener> eventListeners) {
        this.objectMapper = objectMapper;
        this.eventListeners = eventListeners;
    }

    /** 顾客通道：订阅时绑定的订单 id + 连接本体。 */
    private record CustomerChannel(Long orderId, SseEmitter emitter) {
    }

    /**
     * 顾客订阅指定订单的状态事件（LLD 11.1 {@code GET /api/customer/orders/events?orderId=}）。
     *
     * @param customerId 当前登录顾客（已由 JWT 过滤器解析，且已校验订单归属）
     * @param orderId    订阅的订单主键
     * @param status     订单当前状态（连接即补发的快照，用于断线重连补齐）
     * @param pickupCode 订单当前取餐码
     * @return 交给 Spring MVC 的 SSE 发射器
     */
    public SseEmitter subscribeCustomer(Long customerId, Long orderId, String status, String pickupCode) {
        SseEmitter emitter = new SseEmitter(0L);
        CustomerChannel channel = new CustomerChannel(orderId, emitter);
        CustomerChannel previous = customerChannels.put(customerId, channel);
        if (previous != null) {
            previous.emitter().complete();
        }
        emitter.onCompletion(() -> customerChannels.remove(customerId, channel));
        emitter.onTimeout(() -> customerChannels.remove(customerId, channel));
        emitter.onError(e -> customerChannels.remove(customerId, channel));
        // 早期 send 会缓存在 emitter 内，待 handler 初始化后补发（ResponseBodyEmitter 语义）
        send(emitter, OrderEvent.statusChanged(orderId, status, pickupCode));
        log.info("[T43] 顾客 SSE 已连接 customerId={} orderId={} 当前顾客通道数={}",
                customerId, orderId, customerChannels.size());
        return emitter;
    }

    /**
     * 商家看板订阅（LLD 11.1 {@code GET /api/admin/board/events}）。
     *
     * <p>推送口径为「有事件即推」：前端每收到一条事件刷新一次看板，从而复用既有
     * 差集判定（新单提示音 + 30 秒高亮），新单提醒延迟远低于 SRS 9.1 的 5 秒要求。</p>
     */
    public SseEmitter subscribeAdmin() {
        SseEmitter emitter = new SseEmitter(0L);
        SseEmitter previous = adminChannel.getAndSet(emitter);
        if (previous != null) {
            previous.complete();
        }
        emitter.onCompletion(() -> adminChannel.compareAndSet(emitter, null));
        emitter.onTimeout(() -> adminChannel.compareAndSet(emitter, null));
        emitter.onError(e -> adminChannel.compareAndSet(emitter, null));
        log.info("[T43] 商家 SSE 已连接");
        return emitter;
    }

    /**
     * 发布订单状态变更（六态全部）：商家通道全量下发；顾客通道按 customerId + orderId 过滤下发。
     * 事务内调用时于提交后投递。
     */
    public void publishStatusChanged(Order order) {
        publish(OrderEvent.statusChanged(order.getId(), order.getStatus(), order.getPickupCode()),
                order.getCustomerId());
    }

    /** 发布出餐就绪（COMPLETED）：在状态变更之外追加一条（LLD 11.1 事件结构）。 */
    public void publishPickupReady(Order order) {
        publish(OrderEvent.pickupReady(order.getId(), order.getPickupCode()), order.getCustomerId());
    }

    /** 心跳：每 15s 向全部通道发送注释行；发送失败即清理该通道。 */
    @Scheduled(fixedRate = HEARTBEAT_MILLIS)
    public void heartbeat() {
        SseEmitter admin = adminChannel.get();
        if (admin != null) {
            ping(admin);
        }
        customerChannels.values().forEach(channel -> ping(channel.emitter()));
    }

    private void publish(OrderEvent event, Long customerId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(event, customerId);
                }
            });
        } else {
            dispatch(event, customerId);
        }
    }

    private void dispatch(OrderEvent event, Long customerId) {
        // 非 SSE 出口（T44 微信订阅消息等）：与 SSE 消费同一份事件体，内容同源（LLD 11.4）
        notifyListeners(event);
        SseEmitter admin = adminChannel.get();
        if (admin != null) {
            send(admin, event);
        }
        if (customerId == null) {
            // 柜台单无归属顾客（customer_id 为 NULL），仅进商家通道
            return;
        }
        CustomerChannel channel = customerChannels.get(customerId);
        if (channel != null && (channel.orderId() == null || channel.orderId().equals(event.orderId()))) {
            send(channel.emitter(), event);
        }
    }

    /**
     * 回调全部 {@link OrderEventListener}，逐个隔离异常。
     *
     * <p>旁路出口（订阅消息）失败绝不能反噬主链路：SRS 9.3 要求推送失败不影响全流程，
     * 因此这里吞掉异常并记日志；同时逐个 try-catch，一个监听器抛错不影响其余监听器。</p>
     */
    private void notifyListeners(OrderEvent event) {
        eventListeners.forEach(listener -> {
            try {
                listener.onOrderEvent(event);
            } catch (Exception e) {
                log.warn("[T44] 订单事件监听器执行失败 listener={} type={} orderId={}：{}",
                        listener.getClass().getSimpleName(), event.type(), event.orderId(), e.getMessage());
            }
        });
    }

    private void send(SseEmitter emitter, OrderEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(toJson(event)));
        } catch (Exception e) {
            log.debug("[T43] SSE 下发失败 type={} orderId={}：{}", event.type(), event.orderId(), e.getMessage());
            emitter.completeWithError(e);
        }
    }

    private void ping(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (Exception e) {
            log.debug("[T43] SSE 心跳失败，判定断线：{}", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    /** 事件体序列化为 SSE 的 data 行内容；事件不应拖垮业务主链路，故序列化异常一律兜底。 */
    private String toJson(OrderEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("[T43] SSE 事件序列化失败 type={}：{}", event.type(), e.getMessage());
            return "{\"type\":\"" + event.type() + "\"}";
        }
    }
}
