package com.milktea.order.order.controller;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.exception.GlobalExceptionHandler;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.common.result.PageResult;
import com.milktea.order.order.dto.OptionSnapshot;
import com.milktea.order.order.service.OrderQueryService;
import com.milktea.order.order.service.OrderService;
import com.milktea.order.order.vo.OrderDetailItemVo;
import com.milktea.order.order.vo.OrderDetailVo;
import com.milktea.order.order.vo.OrderListItemVo;
import com.milktea.order.order.vo.OrderStatusVo;
import com.milktea.order.order.vo.PayVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 顾客订单控制器契约测试（LLD 3.3）：
 * <ul>
 *   <li>T12：{@code POST /api/customer/orders/{id}/pay} 路径、响应体结构、错误码透传与身份读取；</li>
 *   <li>T13：{@code GET /active}、{@code GET /}（分页）、{@code GET /{id}}、{@code GET /{id}/status}
 *       四查询端点的路径、响应结构、参数透传与错误码透传。</li>
 * </ul>
 *
 * <p>分页边界归一化（page/size 越界归位）是服务层职责，由 OrderQueryServiceTest 覆盖；
 * 本测试只验证控制器"原样透传参数、原样序列化服务层结果"的契约面。</p>
 */
@DisplayName("T12/T13 顾客订单控制器契约（CustomerOrderController）")
class CustomerOrderControllerTest {

    @FunctionalInterface
    private interface MockMvcCall {
        void execute() throws Exception;
    }

    private OrderService orderService;
    private OrderQueryService orderQueryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        orderQueryService = mock(OrderQueryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new CustomerOrderController(orderService, orderQueryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ------------------------------------------------------------------ T12 pay

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

    // ---------------------------------------------------------------- T13 查询

    @Test
    @DisplayName("T13 active：返回进行中列表，身份取 JWT customerId")
    void activeEndpointContract() throws Exception {
        OrderListItemVo item = new OrderListItemVo();
        item.setId(3001L);
        item.setStatus("PREPARING");
        item.setPickupCode("018");
        item.setTotalAmount("55.00");
        item.setCreatedAt("2026-09-12 14:21:55");
        item.setItems(List.of("珍珠奶茶x1(大杯/少冰/珍珠)"));
        when(orderQueryService.listActive(101L)).thenReturn(List.of(item));

        runAsCustomer(101L, () -> mockMvc.perform(get("/api/customer/orders/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(3001))
                .andExpect(jsonPath("$.data[0].status").value("PREPARING"))
                .andExpect(jsonPath("$.data[0].pickupCode").value("018"))
                .andExpect(jsonPath("$.data[0].totalAmount").value("55.00"))
                .andExpect(jsonPath("$.data[0].items[0]").value("珍珠奶茶x1(大杯/少冰/珍珠)")));

        verify(orderQueryService).listActive(101L);
    }

    @Test
    @DisplayName("T13 历史分页：默认 page=1/size=20 与传参原样透传，响应 {list,total,page,size}")
    void historyPaginationContract() throws Exception {
        when(orderQueryService.listHistory(101L, 1, 20)).thenReturn(PageResult.of(List.of(), 0, 1, 20));
        when(orderQueryService.listHistory(101L, 0, 200)).thenReturn(PageResult.of(List.of(), 3L, 1, 100));

        // 不传参：控制器以默认值 1/20 调服务层
        runAsCustomer(101L, () -> mockMvc.perform(get("/api/customer/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20)));

        // 越界参数原样透传（归一化属服务层，见 OrderQueryServiceTest）
        runAsCustomer(101L, () -> mockMvc.perform(get("/api/customer/orders")
                        .param("page", "0").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(100)));

        verify(orderQueryService).listHistory(101L, 1, 20);
        verify(orderQueryService).listHistory(101L, 0, 200);
    }

    @Test
    @DisplayName("T13 详情：含订单项快照明细的完整字段")
    void detailEndpointContract() throws Exception {
        OrderDetailVo vo = new OrderDetailVo();
        vo.setId(3001L);
        vo.setOrderNo("26091200123");
        vo.setSource("MINI_PROGRAM");
        vo.setStatus("PREPARING");
        vo.setPickupCode("018");
        vo.setTotalAmount("17.00");
        vo.setRemark("少冰");
        vo.setCreatedAt("2026-09-12 14:21:55");
        vo.setPaidAt("2026-09-12 14:22:10");
        vo.setItems(List.of(new OrderDetailItemVo(10L, "珍珠奶茶", 1, "12.00", "17.00",
                List.of(new OptionSnapshot(1L, "杯型", 2L, "大杯", "3.00"),
                        new OptionSnapshot(4L, "加料", 9L, "珍珠", "2.00")),
                "17.00")));
        when(orderQueryService.detail(3001L, 101L)).thenReturn(vo);

        runAsCustomer(101L, () -> mockMvc.perform(get("/api/customer/orders/3001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.orderNo").value("26091200123"))
                .andExpect(jsonPath("$.data.status").value("PREPARING"))
                .andExpect(jsonPath("$.data.items[0].productName").value("珍珠奶茶"))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value("17.00"))
                .andExpect(jsonPath("$.data.items[0].options[0].groupName").value("杯型"))
                .andExpect(jsonPath("$.data.items[0].options[0].optionName").value("大杯"))
                .andExpect(jsonPath("$.data.items[0].options[1].priceDelta").value("2.00")));

        verify(orderQueryService).detail(3001L, 101L);
    }

    @Test
    @DisplayName("T13 详情：他人订单 1005 经全局处理器返回统一 R 体")
    void detailOthersReturns1005Body() throws Exception {
        when(orderQueryService.detail(3001L, 101L)).thenThrow(new BusinessException(ErrorCode.ORDER_NOT_BELONG));

        runAsCustomer(101L, () -> mockMvc.perform(get("/api/customer/orders/3001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1005)));
    }

    @Test
    @DisplayName("T13 轮询：响应仅 status/pickupCode/seq 三字段（轻量契约）")
    void statusEndpointContract() throws Exception {
        when(orderQueryService.status(3001L, 101L)).thenReturn(new OrderStatusVo("PREPARING", "018", 6));

        runAsCustomer(101L, () -> mockMvc.perform(get("/api/customer/orders/3001/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PREPARING"))
                .andExpect(jsonPath("$.data.pickupCode").value("018"))
                .andExpect(jsonPath("$.data.seq").value(6))
                .andExpect(jsonPath("$.data.length()").value(3)));

        verify(orderQueryService).status(3001L, 101L);
    }

    @Test
    @DisplayName("T13 查询端点：无身份兜底 401，不触达服务层")
    void queryMissingPrincipalReturns401() throws Exception {
        mockMvc.perform(get("/api/customer/orders/active"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(orderQueryService);
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
