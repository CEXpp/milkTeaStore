package com.milktea.order.order.service;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.result.PageResult;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderItem;
import com.milktea.order.order.mapper.OrderItemMapper;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.order.vo.OrderListItemVo;
import com.milktea.order.order.vo.OrderStatusVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T13 订单查询服务单测（LLD 3.1 / 3.3）：
 * 分页边界归一化（任务卡用例 page=0、size=200）、归属校验 1005/1004、
 * 轮询 seq 口径、列表商品摘要与脏快照兜底。
 */
@DisplayName("T13 订单查询服务（OrderQueryService）")
class OrderQueryServiceTest {

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private OrderQueryService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        service = new OrderQueryService(orderMapper, orderItemMapper);
    }

    private Order order(long id, Long customerId, String status) {
        Order order = new Order();
        order.setId(id);
        order.setCustomerId(customerId);
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal("14.00"));
        order.setCreatedAt(LocalDateTime.of(2026, 9, 19, 14, 0, 0));
        return order;
    }

    private OrderItem item(long orderId, String productName, int quantity, String optionsJson) {
        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setProductName(productName);
        item.setQuantity(quantity);
        item.setUnitPrice(new BigDecimal("14.00"));
        item.setItemAmount(new BigDecimal("14.00"));
        item.setOptionsSnapshot(optionsJson);
        return item;
    }

    // ------------------------------------------------------------ 分页边界

    @Test
    @DisplayName("分页边界归一化：page=0→1、size=200→100（任务卡用例）")
    void historyNormalizesOutOfRangeParams() {
        when(orderMapper.selectCount(any())).thenReturn(2L);
        when(orderMapper.selectList(any())).thenReturn(List.of(order(1L, 101L, "PAID")));
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        PageResult<OrderListItemVo> result = service.listHistory(101L, 0, 200);

        assertEquals(1, result.getPage(), "page 小于 1 应归一为 1");
        assertEquals(100, result.getSize(), "size 超过 100 应归一为 100");
        assertEquals(2L, result.getTotal());
    }

    @Test
    @DisplayName("分页边界归一化：size=0 归默认 20；page 正常时保持")
    void historyNormalizesZeroSizeToDefault() {
        when(orderMapper.selectCount(any())).thenReturn(0L);

        PageResult<OrderListItemVo> result = service.listHistory(101L, 3, 0);

        assertEquals(3, result.getPage());
        assertEquals(20, result.getSize(), "size<=0 应归默认 20");
    }

    @Test
    @DisplayName("无数据短路：total=0 时不再查询列表")
    void historyShortCircuitsWhenEmpty() {
        when(orderMapper.selectCount(any())).thenReturn(0L);

        PageResult<OrderListItemVo> result = service.listHistory(101L, 1, 20);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getList().isEmpty());
        verify(orderMapper, never()).selectList(any());
    }

    // ------------------------------------------------------------ 归属校验

    @Test
    @DisplayName("详情：他人订单 1005、订单不存在 1004（不泄露订单内容）")
    void detailEnforcesOwnership() {
        when(orderMapper.selectById(3001L)).thenReturn(order(3001L, 999L, "PAID"));
        BusinessException others = assertThrows(BusinessException.class, () -> service.detail(3001L, 101L));
        assertEquals(ErrorCode.ORDER_NOT_BELONG.getCode(), others.getCode());

        when(orderMapper.selectById(4000L)).thenReturn(null);
        BusinessException missing = assertThrows(BusinessException.class, () -> service.detail(4000L, 101L));
        assertEquals(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), missing.getCode());
    }

    @Test
    @DisplayName("轮询状态：他人订单 1005")
    void statusEnforcesOwnership() {
        when(orderMapper.selectById(3001L)).thenReturn(order(3001L, 999L, "PAID"));

        BusinessException e = assertThrows(BusinessException.class, () -> service.status(3001L, 101L));
        assertEquals(ErrorCode.ORDER_NOT_BELONG.getCode(), e.getCode());
    }

    // ------------------------------------------------------------ 轮询 seq

    @Test
    @DisplayName("轮询 seq：已支付单 = 前方 PAID/PREPARING 单数；未支付单恒 0")
    void statusComputesQueueSeq() {
        Order paid = order(3001L, 101L, "PAID");
        paid.setPaidAt(LocalDateTime.of(2026, 9, 19, 14, 22, 10));
        paid.setPickupCode("018");
        when(orderMapper.selectById(3001L)).thenReturn(paid);
        when(orderMapper.selectCount(any())).thenReturn(2L);

        OrderStatusVo vo = service.status(3001L, 101L);
        assertEquals("PAID", vo.status());
        assertEquals("018", vo.pickupCode());
        assertEquals(2L, vo.seq());

        Order pending = order(3002L, 101L, "PENDING_PAYMENT");
        when(orderMapper.selectById(3002L)).thenReturn(pending);
        assertEquals(0L, service.status(3002L, 101L).seq(), "未支付单不参与排队计数");
        assertNull(pending.getPickupCode());
    }

    // ------------------------------------------------------------ 列表摘要

    @Test
    @DisplayName("active 摘要：规格快照拼接为 名x数量(规格/规格)；脏 JSON 兜底无括号")
    void activeSummaryFormatAndFallback() {
        Order paid = order(3001L, 101L, "PAID");
        paid.setPickupCode("018");
        Order preparing = order(3002L, 101L, "PREPARING");
        when(orderMapper.selectList(any())).thenReturn(List.of(paid, preparing));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(
                item(3001L, "珍珠奶茶", 1,
                        "[{\"groupId\":1,\"groupName\":\"杯型\",\"optionId\":2,\"optionName\":\"大杯\",\"priceDelta\":\"3.00\"},"
                                + "{\"groupId\":4,\"groupName\":\"加料\",\"optionId\":9,\"optionName\":\"珍珠\",\"priceDelta\":\"2.00\"}]"),
                item(3002L, "四季春茶", 2, "{bad json")));

        List<OrderListItemVo> list = service.listActive(101L);

        assertEquals(2, list.size());
        assertEquals(List.of("珍珠奶茶x1(大杯/珍珠)"), list.get(0).getItems());
        assertEquals(List.of("四季春茶x2"), list.get(1).getItems(), "脏快照按空规格兜底");
    }
}
