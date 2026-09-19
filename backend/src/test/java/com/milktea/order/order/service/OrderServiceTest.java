package com.milktea.order.order.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.order.dto.OrderCreateRequest;
import com.milktea.order.order.dto.OptionSnapshot;
import com.milktea.order.order.dto.OrderItemRequest;
import com.milktea.order.order.dto.PricedItem;
import com.milktea.order.order.dto.PricingResult;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderItem;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.mapper.OrderItemMapper;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.order.vo.OrderCreateVo;
import com.milktea.order.order.vo.OrderItemVo;
import com.milktea.order.payment.PaymentService;
import com.milktea.order.product.service.PricingService;
import com.milktea.order.shop.entity.ShopConfig;
import com.milktea.order.shop.mapper.ShopConfigMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderService 下单事务单元测试（不依赖数据库）。
 *
 * <p>覆盖：正常下单（快照 / 合计 / 订单号 / expireAt）、暂停接单 1006 不落库、
 * 订单号经 T11 SequenceService 统一发放、计价失败不落库、orderNo 与 expireAt 格式。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** SequenceService 发放的订单号（真实实现为 yyMMdd + 5 位序列）。 */
    private static final String ORDER_NO = "26091200001";

    @Mock
    private PricingService pricingService;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private SequenceService sequenceService;
    @Mock
    private ShopConfigMapper shopConfigMapper;
    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private OrderService orderService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ShopConfig.class);
        TableInfoHelper.initTableInfo(assistant, Order.class);
        TableInfoHelper.initTableInfo(assistant, OrderItem.class);
    }

    /**
     * 纯 Mockito 不解析 {@code @Value}，手动注入支付期限（生产由 application-dev.yml 提供 15）。
     */
    @org.junit.jupiter.api.BeforeEach
    void injectConfig() throws Exception {
        var field = OrderService.class.getDeclaredField("paymentTimeoutMinutes");
        field.setAccessible(true);
        field.set(orderService, 15);
    }

    private static OrderCreateRequest sampleRequest() {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setItems(List.of(new OrderItemRequest(1L, List.of(2L, 4L, 9L, 13L, 14L), 1)));
        req.setRemark("口味偏淡");
        return req;
    }

    private static PricingResult samplePricing() {
        PricedItem pi = new PricedItem();
        pi.setProductId(1L);
        pi.setProductName("珍珠奶茶");
        pi.setBasePrice(new BigDecimal("12.00"));
        pi.setOptions(List.of(
                new OptionSnapshot(1L, "杯型", 2L, "大杯", "3.00"),
                new OptionSnapshot(4L, "加料", 13L, "珍珠", "2.00")));
        pi.setQuantity(1);
        pi.setUnitPrice(new BigDecimal("17.00"));
        pi.setItemAmount(new BigDecimal("17.00"));
        PricingResult result = new PricingResult();
        result.setItems(List.of(pi));
        result.setTotalAmount(new BigDecimal("17.00"));
        return result;
    }

    /** 让 insert 写入自增 id，并捕获实体。 */
    private void stubOrderInsertCapturing(ArgumentCaptor<Order> captor) {
        doAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            o.setId(3001L);
            return null;
        }).when(orderMapper).insert(captor.capture());
    }

    @Test
    @DisplayName("正常下单：订单号=yyMMdd+5位、totalAmount、expireAt=createdAt+15min、含快照项")
    void createOrderNormal() {
        when(shopConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(openConfig());
        when(pricingService.calculatePrice(any(List.class))).thenReturn(samplePricing());
        when(sequenceService.nextOrderNo()).thenReturn(ORDER_NO);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        stubOrderInsertCapturing(orderCaptor);

        OrderCreateVo vo = orderService.createOrder(sampleRequest());

        Order saved = orderCaptor.getValue();
        assertEquals(OrderStatus.PENDING_PAYMENT.name(), saved.getStatus());
        assertEquals(Order.SOURCE_MINI_PROGRAM, saved.getSource());
        assertEquals(new BigDecimal("17.00"), saved.getTotalAmount());
        assertEquals("口味偏淡", saved.getRemark());

        assertEquals(3001L, vo.id());
        assertEquals(ORDER_NO, vo.orderNo());
        assertEquals(OrderStatus.PENDING_PAYMENT.name(), vo.status());
        assertEquals("17.00", vo.totalAmount());

        String expectedExpire = saved.getCreatedAt().plusMinutes(15).format(DATETIME_FMT);
        assertEquals(expectedExpire, vo.expireAt());

        assertEquals(1, vo.items().size());
        OrderItemVo item = vo.items().get(0);
        assertEquals("珍珠奶茶", item.productName());
        assertEquals(1, item.quantity());
        assertEquals("17.00", item.unitPrice());
        assertEquals("17.00", item.itemAmount());
        assertEquals(List.of("大杯", "珍珠"), item.optionNames());

        verify(orderItemMapper, times(1)).insert(any(OrderItem.class));
    }

    @Test
    @DisplayName("暂停接单：抛 1006 且不写 orders / order_item")
    void shopPausedRejects() {
        when(shopConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pausedConfig());
        // 暂停校验在计价之前，pricingService 不会被调用（lenient 避免严格 stub 报错）
        org.mockito.Mockito.lenient().when(pricingService.calculatePrice(any(List.class))).thenReturn(samplePricing());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createOrder(sampleRequest()));
        assertEquals(ErrorCode.SHOP_PAUSED.getCode(), ex.getCode());

        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderItemMapper, never()).insert(any(OrderItem.class));
    }

    @Test
    @DisplayName("订单号统一经 SequenceService 发放（下单不再直连 daily_seq）")
    void orderNoIssuedBySequenceService() {
        when(shopConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(openConfig());
        when(pricingService.calculatePrice(any(List.class))).thenReturn(samplePricing());
        when(sequenceService.nextOrderNo()).thenReturn(ORDER_NO);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        stubOrderInsertCapturing(captor);

        OrderCreateVo vo = orderService.createOrder(sampleRequest());

        verify(sequenceService, times(1)).nextOrderNo();
        assertEquals(ORDER_NO, captor.getValue().getOrderNo());
        assertEquals(ORDER_NO, vo.orderNo());
    }

    @Test
    @DisplayName("计价失败：不写 orders / order_item（事务内异常上抛前无落库）")
    void pricingFailureNoPersist() {
        when(shopConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(openConfig());
        when(pricingService.calculatePrice(any(List.class)))
                .thenThrow(new BusinessException(ErrorCode.SPEC_INVALID));

        assertThrows(BusinessException.class, () -> orderService.createOrder(sampleRequest()));

        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderItemMapper, never()).insert(any(OrderItem.class));
        verify(sequenceService, never()).nextOrderNo();
    }

    private static ShopConfig openConfig() {
        ShopConfig c = new ShopConfig();
        c.setConfigKey("paused");
        c.setConfigValue("false");
        return c;
    }

    private static ShopConfig pausedConfig() {
        ShopConfig c = new ShopConfig();
        c.setConfigKey("paused");
        c.setConfigValue("true");
        return c;
    }
}
