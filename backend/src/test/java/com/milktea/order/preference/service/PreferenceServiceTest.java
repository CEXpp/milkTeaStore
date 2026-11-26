package com.milktea.order.preference.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.milktea.order.ai.decision.PreferenceSnapshot;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderItem;
import com.milktea.order.order.mapper.OrderItemMapper;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.preference.entity.CustomerPreference;
import com.milktea.order.preference.mapper.CustomerPreferenceMapper;
import com.milktea.order.preference.vo.PreferenceVo;
import com.milktea.order.product.entity.Product;
import com.milktea.order.product.entity.SpecGroup;
import com.milktea.order.product.mapper.ProductMapper;
import com.milktea.order.product.mapper.SpecGroupMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 偏好聚合测试：口径的正确性 + 隐私边界。
 *
 * <p>重点守三件事：</p>
 * <ol>
 *   <li><b>只认已成交订单</b>：未付 / 超时 / 已作废的单不得参与（否则「点错又退掉的那杯」会污染偏好）；</li>
 *   <li><b>「一单一次」而不「一杯一次」</b>：一次点三杯不代表反复选择；</li>
 *   <li><b>样本不足宁可不认定</b>：加料组的分母单独统计，防「一单定终身」。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class PreferenceServiceTest {

    private static final Long CUSTOMER = 7L;
    private static final Long SWEETNESS_GROUP = 3L;
    private static final Long TOPPING_GROUP = 4L;
    private static final Long PEARL_MILK_TEA = 1L;

    @Mock
    private CustomerPreferenceMapper preferenceMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderItemMapper orderItemMapper;

    @Mock
    private SpecGroupMapper specGroupMapper;

    @Mock
    private ProductMapper productMapper;

    private PreferenceService service;

    /** 单测无 MyBatis 上下文，需手动初始化实体元数据，否则 LambdaQueryWrapper 无法解析列。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        Stream.of(Order.class, OrderItem.class)
                .forEach(clazz -> TableInfoHelper.initTableInfo(assistant, clazz));
    }

    @BeforeEach
    void setUp() {
        service = new PreferenceService(preferenceMapper, orderMapper, orderItemMapper,
                specGroupMapper, productMapper);
        ReflectionTestUtils.setField(service, "minSampleOrders", 3);
        ReflectionTestUtils.setField(service, "modeRatio", 0.6);
        ReflectionTestUtils.setField(service, "windowOrders", 50);
        ReflectionTestUtils.setField(service, "staleHours", 24);
    }

    @Test
    @DisplayName("零订单：认定不出任何偏好，但照实落一行 sample_orders=0（把「没历史」与「没算过」分开）")
    void noOrdersWritesEmptyRow() {
        givenOrders(List.of());

        service.rebuild(CUSTOMER);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(preferenceMapper).upsert(eq(CUSTOMER), json.capture(), isNull(), eq(0), anyString(),
                any(LocalDateTime.class));
        assertNull(json.getValue(), "无认定项时不写 JSON（而不是写一个空数组）");
    }

    @Test
    @DisplayName("样本不足阈值：规格与常点商品都不认定（防一单定终身）")
    void insufficientSamplesAreNotInferred() {
        givenSpecGroups();
        givenOrders(List.of(order(1L), order(2L)));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(
                item(1L, SWEETNESS_GROUP, "半糖"), item(2L, SWEETNESS_GROUP, "半糖")));

        service.rebuild(CUSTOMER);

        verify(preferenceMapper).upsert(eq(CUSTOMER), isNull(), isNull(), eq(2), anyString(),
                any(LocalDateTime.class));
    }

    @Test
    @DisplayName("众数达阈值：照实认定；频次按订单数算，不按杯数")
    void infersModeAboveThreshold() {
        givenSpecGroups();
        List<Order> orders = new ArrayList<>();
        List<OrderItem> items = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            orders.add(order((long) i));
            items.add(item((long) i, SWEETNESS_GROUP, i <= 7 ? "半糖" : "全糖"));
        }
        givenOrders(orders);
        when(orderItemMapper.selectList(any())).thenReturn(items);

        service.rebuild(CUSTOMER);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> favorite = ArgumentCaptor.forClass(String.class);
        verify(preferenceMapper).upsert(eq(CUSTOMER), json.capture(), favorite.capture(), eq(10),
                anyString(), any(LocalDateTime.class));
        assertTrue(json.getValue().contains("\"groupCode\":\"SWEETNESS\""), json.getValue());
        assertTrue(json.getValue().contains("半糖"), json.getValue());
        assertFalse(json.getValue().contains("全糖"), "未达阈值的选项不得写入：" + json.getValue());
        assertEquals("珍珠奶茶", favorite.getValue(), "商品查不到时回落快照名");
    }

    @Test
    @DisplayName("频次恰好不过阈值：不认定（0.5 < 0.6 的边界）")
    void doesNotInferBelowRatio() {
        givenSpecGroups();
        List<Order> orders = new ArrayList<>();
        List<OrderItem> items = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            orders.add(order((long) i));
            items.add(item((long) i, SWEETNESS_GROUP, i <= 5 ? "半糖" : "全糖"));
        }
        givenOrders(orders);
        when(orderItemMapper.selectList(any())).thenReturn(items);

        service.rebuild(CUSTOMER);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(preferenceMapper).upsert(eq(CUSTOMER), json.capture(), anyString(), eq(10),
                anyString(), any(LocalDateTime.class));
        assertNull(json.getValue(), "没有任何选项过阈值时不应写 JSON");
    }

    @Test
    @DisplayName("加料组的分子分母按「挂了该组的订单数」算，不被未挂该组的订单稀释")
    void computesRatioPerGroupNotPerAllOrders() {
        givenSpecGroups();
        List<Order> orders = new ArrayList<>();
        List<OrderItem> items = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            orders.add(order((long) i));
            items.add(item((long) i, SWEETNESS_GROUP, "半糖"));
            if (i <= 3) {
                items.add(item((long) i, TOPPING_GROUP, "珍珠"));
            }
        }
        givenOrders(orders);
        when(orderItemMapper.selectList(any())).thenReturn(items);

        service.rebuild(CUSTOMER);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(preferenceMapper).upsert(eq(CUSTOMER), json.capture(), anyString(), eq(10),
                anyString(), any(LocalDateTime.class));
        assertTrue(json.getValue().contains("\"groupCode\":\"TOPPING\""),
                "加料组分母是 3 而非 10，应被认定：" + json.getValue());
        assertTrue(json.getValue().contains("珍珠"), json.getValue());
    }

    @Test
    @DisplayName("一单点多杯同规格：只计一次（一杯点三次 ≠ 一次点三杯）")
    void countsOnePerOrderNotPerCup() {
        givenSpecGroups();
        List<Order> orders = List.of(order(1L), order(2L), order(3L));
        List<OrderItem> items = new ArrayList<>();
        for (long orderId = 1; orderId <= 3; orderId++) {
            for (int cup = 0; cup < 3; cup++) {
                items.add(item(orderId, SWEETNESS_GROUP, "半糖"));
            }
        }
        givenOrders(orders);
        when(orderItemMapper.selectList(any())).thenReturn(items);

        service.rebuild(CUSTOMER);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(preferenceMapper).upsert(eq(CUSTOMER), json.capture(), anyString(), eq(3),
                anyString(), any(LocalDateTime.class));
        assertTrue(json.getValue().contains("半糖"), "每单只计一次后占比 3/3，应认定：" + json.getValue());
    }

    @Test
    @DisplayName("聚合口径与隔离：只查已成交三态，且限定在当前顾客名下")
    void queriesOnlySettledOrdersOfThisCustomer() {
        givenOrders(List.of());

        service.rebuild(CUSTOMER);

        ArgumentCaptor<Wrapper<Order>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderMapper).selectList(captor.capture());
        List<String> bound = flatten(boundValues(captor.getValue()));
        assertTrue(bound.contains("PAID"), bound.toString());
        assertTrue(bound.contains("PREPARING"), bound.toString());
        assertTrue(bound.contains("COMPLETED"), bound.toString());
        assertFalse(bound.contains("PENDING_PAYMENT"), "未付款的单不得参与聚合：" + bound);
        assertFalse(bound.contains("CLOSED"), "超时关单不得参与聚合：" + bound);
        assertFalse(bound.contains("VOIDED"), "已作废退款不得参与聚合：" + bound);
        assertTrue(bound.contains(String.valueOf(CUSTOMER)), "必须限定顾客，不能串号：" + bound);
    }

    @Test
    @DisplayName("重算幂等：同一份订单历史连续两次得到同样的结论")
    void rebuildIsIdempotent() {
        givenSpecGroups();
        givenOrders(List.of(order(1L), order(2L), order(3L)));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(
                item(1L, SWEETNESS_GROUP, "半糖"), item(2L, SWEETNESS_GROUP, "半糖"),
                item(3L, SWEETNESS_GROUP, "半糖")));

        service.rebuild(CUSTOMER);
        service.rebuild(CUSTOMER);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(preferenceMapper, Mockito.times(2)).upsert(eq(CUSTOMER), json.capture(), anyString(),
                anyInt(), anyString(), any(LocalDateTime.class));
        assertEquals(json.getAllValues().get(0), json.getAllValues().get(1));
    }

    @Test
    @DisplayName("偏好新鲜时不重算（惰性补算只在过期时发生，避免每次进页面扫两遍表）")
    void freshRowIsNotRebuilt() {
        when(preferenceMapper.selectOne(any())).thenReturn(
                row("[{\"groupCode\":\"SWEETNESS\",\"optionNames\":[\"半糖\"]}]", 8, LocalDateTime.now().minusHours(1)));

        PreferenceSnapshot snapshot = service.snapshot(CUSTOMER);

        assertFalse(snapshot.isEmpty());
        assertEquals(List.of("半糖"), snapshot.names("SWEETNESS"));
        verify(orderMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("偏好过期时惰性补算")
    void staleRowIsRebuilt() {
        when(preferenceMapper.selectOne(any())).thenReturn(row(null, 0, LocalDateTime.now().minusHours(48)));
        when(orderMapper.selectList(any())).thenReturn(List.of());

        service.snapshot(CUSTOMER);

        verify(orderMapper).selectList(any());
    }

    @Test
    @DisplayName("样本不足时决策层拿到空快照（新客不注入任何默认值）")
    void snapshotIsEmptyWhenSamplesInsufficient() {
        when(preferenceMapper.selectOne(any())).thenReturn(
                row("[{\"groupCode\":\"SWEETNESS\",\"optionNames\":[\"半糖\"]}]", 2, LocalDateTime.now()));

        assertTrue(service.snapshot(CUSTOMER).isEmpty());
    }

    @Test
    @DisplayName("口味档案视图：样本不足走空态；样本足量按规格组顺序展示中文组名")
    void viewReflectsAvailabilityAndGroupNames() {
        givenSpecGroups();
        when(preferenceMapper.selectOne(any())).thenReturn(
                row("[{\"groupCode\":\"SWEETNESS\",\"optionNames\":[\"半糖\"]}]", 2, LocalDateTime.now()));
        PreferenceVo empty = service.view(CUSTOMER);
        assertFalse(empty.available());
        assertTrue(empty.groups().isEmpty());

        when(preferenceMapper.selectOne(any())).thenReturn(
                row("[{\"groupCode\":\"SWEETNESS\",\"optionNames\":[\"半糖\"]}]", 9, LocalDateTime.now()));
        PreferenceVo view = service.view(CUSTOMER);
        assertTrue(view.available());
        assertEquals(9, view.sampleOrders());
        assertEquals(1, view.groups().size());
        assertEquals("甜度", view.groups().get(0).name(), "展示层要中文组名，不是 code");
        assertEquals(List.of("半糖"), view.groups().get(0).optionNames());
    }

    @Test
    @DisplayName("商品改名：常点商品取当前名称而非快照旧名")
    void favoriteUsesCurrentProductName() {
        givenSpecGroups();
        givenOrders(List.of(order(1L), order(2L), order(3L)));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(
                item(1L, SWEETNESS_GROUP, "半糖"), item(2L, SWEETNESS_GROUP, "半糖"),
                item(3L, SWEETNESS_GROUP, "半糖")));
        Product renamed = new Product();
        renamed.setId(PEARL_MILK_TEA);
        renamed.setName("珍珠奶茶（新）");
        when(productMapper.selectById(PEARL_MILK_TEA)).thenReturn(renamed);

        service.rebuild(CUSTOMER);

        ArgumentCaptor<String> favorite = ArgumentCaptor.forClass(String.class);
        verify(preferenceMapper).upsert(eq(CUSTOMER), anyString(), favorite.capture(), anyInt(),
                anyString(), any(LocalDateTime.class));
        assertEquals("珍珠奶茶（新）", favorite.getValue());
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 桩：规格组目录（快照里存的是 groupId，需要它反查组 code）。 */
    private void givenSpecGroups() {
        when(specGroupMapper.selectList(any())).thenReturn(List.of(
                specGroup(SWEETNESS_GROUP, "SWEETNESS", "甜度", 3),
                specGroup(TOPPING_GROUP, "TOPPING", "加料", 4)));
    }

    /** 桩：已成交订单查询结果，并让偏好行不存在（走完整重算路径）。 */
    private void givenOrders(List<Order> orders) {
        when(orderMapper.selectList(any())).thenReturn(orders);
        when(preferenceMapper.selectOne(any())).thenReturn(null);
    }

    private Order order(Long id) {
        Order order = new Order();
        order.setId(id);
        order.setCustomerId(CUSTOMER);
        order.setStatus("COMPLETED");
        order.setCreatedAt(LocalDateTime.now());
        return order;
    }

    private OrderItem item(Long orderId, Long groupId, String optionName) {
        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setProductId(PEARL_MILK_TEA);
        item.setProductName("珍珠奶茶");
        item.setQuantity(1);
        item.setOptionsSnapshot("[{\"groupId\":" + groupId + ",\"groupName\":\"G\",\"optionId\":1,"
                + "\"optionName\":\"" + optionName + "\",\"priceDelta\":\"0.00\"}]");
        return item;
    }

    private SpecGroup specGroup(Long id, String code, String name, int sortOrder) {
        SpecGroup group = new SpecGroup();
        group.setId(id);
        group.setCode(code);
        group.setName(name);
        group.setMultiSelect("TOPPING".equals(code) ? 1 : 0);
        group.setSortOrder(sortOrder);
        return group;
    }

    private CustomerPreference row(String json, int sampleOrders, LocalDateTime builtAt) {
        CustomerPreference row = new CustomerPreference();
        row.setId(1L);
        row.setCustomerId(CUSTOMER);
        row.setOptionNamesJson(json);
        row.setSampleOrders(sampleOrders);
        row.setSource(CustomerPreference.SOURCE_INFERRED);
        row.setBuiltAt(builtAt);
        return row;
    }

    /**
     * 读条件包装器上已绑定的参数值。
     *
     * <p>必须先调 {@code getSqlSegment()}：MyBatis-Plus 的 {@code paramNameValuePairs} 是在
     * SQL 片段渲染时才写入的，只构造 wrapper 的话读出来是空的（会得到一个「假通过」的断言）。</p>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Collection<Object> boundValues(Wrapper<?> wrapper) {
        AbstractWrapper abstractWrapper = (AbstractWrapper) wrapper;
        abstractWrapper.getSqlSegment();
        return abstractWrapper.getParamNameValuePairs().values();
    }

    private List<String> flatten(Collection<Object> values) {
        List<String> flattened = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Collection<?> collection) {
                for (Object element : collection) {
                    flattened.add(String.valueOf(element));
                }
            } else {
                flattened.add(String.valueOf(value));
            }
        }
        return flattened;
    }
}
