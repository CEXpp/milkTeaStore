package com.milktea.order.preference.listener;

import com.milktea.order.order.entity.Order;
import com.milktea.order.order.event.OrderEvent;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.preference.service.PreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 偏好刷新监听器测试：触发口径（哪些事件该刷、哪些坚决不刷）。
 *
 * <p>监听器在生产里是异步的；测试通过覆盖 {@code submit} 把任务改为同步执行，
 * 这样「是否被触发」是确定性断言，而不是靠 sleep 碰运气。</p>
 */
@ExtendWith(MockitoExtension.class)
class PreferenceRefreshListenerTest {

    private static final Long ORDER_ID = 3001L;
    private static final Long CUSTOMER = 7L;

    @Mock
    private PreferenceService preferenceService;

    @Mock
    private OrderMapper orderMapper;

    private PreferenceRefreshListener listener;

    @BeforeEach
    void setUp() {
        listener = new PreferenceRefreshListener(preferenceService, orderMapper) {
            @Override
            void submit(Runnable task) {
                task.run();
            }
        };
    }

    @Test
    @DisplayName("PAID：触发刷新")
    void refreshesOnPaid() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(CUSTOMER));
        listener.onOrderEvent(OrderEvent.statusChanged(ORDER_ID, "PAID", "018"));
        verify(preferenceService).rebuild(CUSTOMER);
    }

    @Test
    @DisplayName("COMPLETED：触发刷新（重算幂等，取餐后再对齐一次）")
    void refreshesOnCompleted() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(CUSTOMER));
        listener.onOrderEvent(OrderEvent.statusChanged(ORDER_ID, "COMPLETED", "018"));
        verify(preferenceService).rebuild(CUSTOMER);
    }

    @Test
    @DisplayName("未付款 / 超时关单 / 已作废：一律不刷新")
    void neverRefreshesForUnsettledTransitions() {
        listener.onOrderEvent(OrderEvent.statusChanged(ORDER_ID, "PENDING_PAYMENT", null));
        listener.onOrderEvent(OrderEvent.statusChanged(ORDER_ID, "CLOSED", null));
        listener.onOrderEvent(OrderEvent.statusChanged(ORDER_ID, "VOIDED", "018"));
        listener.onOrderEvent(OrderEvent.statusChanged(ORDER_ID, "PREPARING", "018"));
        verify(preferenceService, never()).rebuild(org.mockito.ArgumentMatchers.any());
        verify(orderMapper, never()).selectById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("PICKUP_READY：不重复触发（同一次出餐只算一次）")
    void ignoresPickupReadyEvent() {
        listener.onOrderEvent(OrderEvent.pickupReady(ORDER_ID, "018"));
        verify(preferenceService, never()).rebuild(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("柜台单（无归属顾客）：静默跳过，没有画像可言")
    void skipsCounterOrderWithoutCustomer() {
        Order counter = order(null);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(counter);
        listener.onOrderEvent(OrderEvent.statusChanged(ORDER_ID, "PAID", "018"));
        verify(preferenceService, never()).rebuild(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("订单已不存在：静默跳过，不抛异常")
    void skipsMissingOrder() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);
        listener.onOrderEvent(OrderEvent.statusChanged(ORDER_ID, "PAID", "018"));
        verify(preferenceService, never()).rebuild(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("刷新失败：吞掉异常（旁路能力失败不得反噬订单主链路）")
    void swallowsRefreshFailure() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(CUSTOMER));
        when(preferenceService.rebuild(CUSTOMER)).thenThrow(new IllegalStateException("db down"));

        listener.onOrderEvent(OrderEvent.statusChanged(ORDER_ID, "PAID", "018"));

        verify(preferenceService).rebuild(CUSTOMER);
    }

    private Order order(Long customerId) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setCustomerId(customerId);
        order.setStatus("PAID");
        return order;
    }
}
