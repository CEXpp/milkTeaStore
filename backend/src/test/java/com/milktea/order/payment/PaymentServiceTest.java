package com.milktea.order.payment;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T12 支付策略路由单测（LLD 5.1）：按 {@code payment.active-channel} 选择实现，
 * 真店切渠道只改配置 + 新增实现，调用方零改动。
 */
@DisplayName("T12 支付策略路由（PaymentService / MockPaymentProvider）")
class PaymentServiceTest {

    /** 测试替身：记录被派发的订单与金额，返回预设结果。 */
    private static final class RecordingProvider implements PaymentProvider {

        private final String channel;
        private final PayResult result;
        private Long calledOrderId;
        private BigDecimal calledAmount;

        private RecordingProvider(String channel, PayResult result) {
            this.channel = channel;
            this.result = result;
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public PayResult pay(Long orderId, BigDecimal amount) {
            this.calledOrderId = orderId;
            this.calledAmount = amount;
            return result;
        }
    }

    @Test
    @DisplayName("按配置路由到激活渠道：配置值大小写不敏感，非激活实现不被调用")
    void routesToConfiguredChannel() {
        RecordingProvider mock = new RecordingProvider("MOCK", PayResult.success("MOCK-1"));
        RecordingProvider wechat = new RecordingProvider("WECHAT", PayResult.success("WX-1"));
        PaymentService service = new PaymentService(List.of(mock, wechat), "wechat");

        PayResult result = service.pay(3001L, new BigDecimal("17.00"));

        assertEquals("WECHAT", service.activeChannel());
        assertTrue(result.isSuccess());
        assertEquals("WX-1", result.getTransactionId());
        assertEquals(3001L, wechat.calledOrderId, "激活渠道应收到派发");
        assertEquals(new BigDecimal("17.00"), wechat.calledAmount);
        assertNull(mock.calledOrderId, "非激活渠道不应被调用");
    }

    @Test
    @DisplayName("激活渠道无实现时启动即失败（fail fast，500）")
    void unknownChannelFailsFast() {
        RecordingProvider mock = new RecordingProvider("MOCK", PayResult.success("MOCK-1"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> new PaymentService(List.of(mock), "alipay"));

        assertEquals(ErrorCode.INTERNAL_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("Mock 实现：确认即成功，交易号 MOCK- + UUID 简写（32 位十六进制），每次不同")
    void mockProviderSucceedsImmediatelyWithMockTransactionId() {
        MockPaymentProvider provider = new MockPaymentProvider();

        PayResult first = provider.pay(3001L, new BigDecimal("17.00"));
        PayResult second = provider.pay(3001L, new BigDecimal("17.00"));

        assertEquals("MOCK", provider.channel());
        assertTrue(first.isSuccess());
        assertTrue(first.getTransactionId().matches("MOCK-[0-9a-f]{32}"),
                "交易号应为 MOCK- + UUID 简写，实际：" + first.getTransactionId());
        assertFalse(first.getTransactionId().equals(second.getTransactionId()), "交易号应逐笔唯一");
        assertNull(first.getMessage());
    }

    @Test
    @DisplayName("失败结果：success=false 且携带失败原因，无交易号")
    void failureResultCarriesMessage() {
        PayResult failure = PayResult.failure("渠道拒付");

        assertFalse(failure.isSuccess());
        assertNull(failure.getTransactionId());
        assertEquals("渠道拒付", failure.getMessage());
    }
}
