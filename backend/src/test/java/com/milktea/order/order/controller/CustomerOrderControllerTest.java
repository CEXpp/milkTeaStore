package com.milktea.order.order.controller;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.exception.GlobalExceptionHandler;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.order.service.OrderService;
import com.milktea.order.order.vo.PayVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T12 pay 端点契约测试（LLD 3.3）：
 * {@code POST /api/customer/orders/{id}/pay} 的路径、响应体结构、错误码透传与身份读取。
 */
@DisplayName("T12 pay 端点契约（CustomerOrderController）")
class CustomerOrderControllerTest {

    @FunctionalInterface
    private interface MockMvcCall {
        void execute() throws Exception;
    }

    private OrderService orderService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new CustomerOrderController(orderService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("支付成功：data={id,status,pickupCode,paidAt,payChannel}，paidAt 为 yyyy-MM-dd HH:mm:ss")
    void payEndpointContract() throws Exception {
        PayVo vo = new PayVo();
        vo.setId(3001L);
        vo.setStatus("PAID");
        vo.setPickupCode("018");
        vo.setPaidAt(LocalDateTime.of(2026, 9, 12, 14, 22, 10));
        vo.setPayChannel("MOCK");
        when(orderService.pay(3001L, 101L)).thenReturn(vo);

        runAsCustomer(101L, () -> mockMvc.perform(post("/api/customer/orders/3001/pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(3001))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.pickupCode").value("018"))
                .andExpect(jsonPath("$.data.paidAt").value("2026-09-12 14:22:10"))
                .andExpect(jsonPath("$.data.payChannel").value("MOCK")));

        verify(orderService).pay(3001L, 101L);
    }

    @Test
    @DisplayName("重复支付：业务码 1004 经全局处理器返回统一 R 体")
    void duplicatePayReturns1004Body() throws Exception {
        when(orderService.pay(3001L, 101L)).thenThrow(new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT));

        runAsCustomer(101L, () -> mockMvc.perform(post("/api/customer/orders/3001/pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1004)));
    }

    @Test
    @DisplayName("支付他人订单：业务码 1005")
    void payOthersReturns1005Body() throws Exception {
        when(orderService.pay(3001L, 101L)).thenThrow(new BusinessException(ErrorCode.ORDER_NOT_BELONG));

        runAsCustomer(101L, () -> mockMvc.perform(post("/api/customer/orders/3001/pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1005)));
    }

    @Test
    @DisplayName("无请求身份（跳过鉴权直调）：兜底 401，不触达服务层")
    void missingPrincipalReturns401() throws Exception {
        mockMvc.perform(post("/api/customer/orders/3001/pay"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(orderService);
    }

    /** 在顾客身份作用域内执行请求（等价于 JWT 过滤器绑定 AuthContext 后的调用链）。 */
    private void runAsCustomer(long customerId, MockMvcCall call) throws Exception {
        AssertionError[] assertionFailure = new AssertionError[1];
        Exception[] unexpected = new Exception[1];
        AuthContext.runWith(AuthContext.Principal.customer(customerId, "openid-" + customerId), () -> {
            try {
                call.execute();
            } catch (AssertionError e) {
                assertionFailure[0] = e;
            } catch (Exception e) {
                unexpected[0] = e;
            }
        });
        if (assertionFailure[0] != null) {
            throw assertionFailure[0];
        }
        if (unexpected[0] != null) {
            throw unexpected[0];
        }
    }
}
