package com.milktea.order.order.service;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.event.OrderEventPublisher;
import com.milktea.order.order.mapper.OrderItemMapper;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.payment.PaymentService;
import com.milktea.order.product.service.PricingService;
import com.milktea.order.shop.mapper.ShopConfigMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    @Test
    void expiredPendingOrderCannotReachPaymentProviderBeforeCleanup() {
        var orders = mock(OrderMapper.class);
        var payments = mock(PaymentService.class);
        var sequences = mock(SequenceService.class);
        var events = mock(OrderEventPublisher.class);
        var service = new OrderService(
                mock(PricingService.class), orders, mock(OrderItemMapper.class),
                sequences, mock(ShopConfigMapper.class), payments, events);
        ReflectionTestUtils.setField(service, "paymentTimeoutMinutes", 15);

        var order = new Order();
        order.setId(5L);
        order.setCustomerId(7L);
        order.setStatus(OrderStatus.PENDING_PAYMENT.name());
        order.setCreatedAt(LocalDateTime.now().minusMinutes(16));
        when(orders.selectById(5L)).thenReturn(order);

        assertThatThrownBy(() -> service.pay(5L, 7L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.getCode())
                                .isEqualTo(ErrorCode.ORDER_STATUS_CONFLICT.getCode()))
                .hasMessage("订单已超过支付期限");
        verifyNoInteractions(payments, sequences, events);
    }
}
