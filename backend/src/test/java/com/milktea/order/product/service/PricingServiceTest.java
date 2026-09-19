package com.milktea.order.product.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.order.dto.OptionSnapshot;
import com.milktea.order.order.dto.OrderItemRequest;
import com.milktea.order.order.dto.PricedItem;
import com.milktea.order.order.dto.PricingResult;
import com.milktea.order.product.entity.Product;
import com.milktea.order.product.entity.ProductSpecGroup;
import com.milktea.order.product.entity.SpecGroup;
import com.milktea.order.product.entity.SpecOption;
import com.milktea.order.product.mapper.ProductMapper;
import com.milktea.order.product.mapper.ProductSpecGroupMapper;
import com.milktea.order.product.mapper.SpecGroupMapper;
import com.milktea.order.product.mapper.SpecOptionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 计价引擎单元测试（不依赖数据库）。
 *
 * <p>基准数据对齐 V2__init_data.sql：珍珠奶茶 12.00；大杯 +3.00；珍珠 / 椰果各 +2.00；
 * 温度 / 甜度不计价；杯型、温度、甜度为单选组，加料为多选组。</p>
 */
@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    private static final Long PRODUCT_ID = 1L;
    private static final Long CUP_SIZE_GROUP = 1L;
    private static final Long TEMPERATURE_GROUP = 2L;
    private static final Long SWEETNESS_GROUP = 3L;
    private static final Long TOPPING_GROUP = 4L;

    private static final Long MID_CUP = 1L;
    private static final Long LARGE_CUP = 2L;
    private static final Long LESS_ICE = 4L;
    private static final Long HALF_SUGAR = 9L;
    private static final Long PEARL = 13L;
    private static final Long COCONUT = 14L;
    private static final Long DISABLED_PUDDING = 15L;

    @Mock
    private ProductMapper productMapper;
    @Mock
    private SpecGroupMapper specGroupMapper;
    @Mock
    private SpecOptionMapper specOptionMapper;
    @Mock
    private ProductSpecGroupMapper productSpecGroupMapper;

    @InjectMocks
    private PricingService pricingService;

    /**
     * 单测无 MyBatis 上下文，需手动初始化实体元数据，否则 LambdaQueryWrapper 无法解析列。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        Stream.of(Product.class, SpecGroup.class, SpecOption.class, ProductSpecGroup.class)
                .forEach(clazz -> TableInfoHelper.initTableInfo(assistant, clazz));
    }

    @Test
    @DisplayName("合法组合：大杯 +3 与双加料各 +2 全部计入，单项金额 = 单杯价 × 数量")
    void unitPriceIncludesCupSizeDeltaAndBothToppings() {
        givenProductOnSale();
        givenAllFourSpecGroups();
        givenOptionsFromSeed();

        PricedItem item = pricingService.calculatePrice(
                new OrderItemRequest(PRODUCT_ID, List.of(LARGE_CUP, LESS_ICE, HALF_SUGAR, PEARL, COCONUT), 2));

        assertMoney("12.00", item.getBasePrice());
        assertMoney("19.00", item.getUnitPrice());
        assertMoney("38.00", item.getItemAmount());
        assertEquals(2, item.getQuantity());
        assertEquals("珍珠奶茶", item.getProductName());
        assertEquals(5, item.getOptions().size());
    }

    @Test
    @DisplayName("多选组（加料）允许 0 项：只加杯型/温度/甜度时价格 = 基础价")
    void multiSelectGroupAllowsZeroOption() {
        givenProductOnSale();
        givenAllFourSpecGroups();
        givenOptionsFromSeed();

        PricedItem item = pricingService.calculatePrice(
                new OrderItemRequest(PRODUCT_ID, List.of(MID_CUP, LESS_ICE, HALF_SUGAR), 1));

        assertMoney("12.00", item.getUnitPrice());
        assertMoney("12.00", item.getItemAmount());
        assertEquals(3, item.getOptions().size());
    }

    @Test
    @DisplayName("整单计价：totalAmount = Σ itemAmount")
    void batchCalculateSumsTotalAmount() {
        givenProductOnSale();
        givenAllFourSpecGroups();
        givenOptionsFromSeed();

        PricingResult result = pricingService.calculatePrice(List.of(
                new OrderItemRequest(PRODUCT_ID, List.of(LARGE_CUP, LESS_ICE, HALF_SUGAR, PEARL, COCONUT), 2),
                new OrderItemRequest(PRODUCT_ID, List.of(MID_CUP, LESS_ICE, HALF_SUGAR), 1)));

        assertEquals(2, result.getItems().size());
        assertMoney("38.00", result.getItems().get(0).getItemAmount());
        assertMoney("12.00", result.getItems().get(1).getItemAmount());
        assertMoney("50.00", result.getTotalAmount());
    }

    @Test
    @DisplayName("快照按规格组/规格项升序：大杯、少冰、半糖、珍珠、椰果，价差两位小数")
    void snapshotOrderedByGroupThenOption() {
        givenProductOnSale();
        givenAllFourSpecGroups();
        givenOptionsFromSeed();

        PricedItem item = pricingService.calculatePrice(
                new OrderItemRequest(PRODUCT_ID, List.of(COCONUT, PEARL, LARGE_CUP, LESS_ICE, HALF_SUGAR), 1));

        assertEquals(List.of("大杯", "少冰", "半糖", "珍珠", "椰果"),
                item.getOptions().stream().map(OptionSnapshot::getOptionName).toList());
        assertEquals(List.of("杯型", "温度", "甜度", "加料", "加料"),
                item.getOptions().stream().map(OptionSnapshot::getGroupName).toList());
        assertEquals(List.of("3.00", "0.00", "0.00", "2.00", "2.00"),
                item.getOptions().stream().map(OptionSnapshot::getPriceDelta).toList());
    }

    @Test
    @DisplayName("缺必选单选组（未选甜度）抛 1003")
    void missingRequiredSingleSelectGroupThrows1003() {
        givenProductOnSale();
        givenAllFourSpecGroups();
        givenOptionsFromSeed();

        BusinessException ex = assertThrows(BusinessException.class, () -> pricingService.calculatePrice(
                new OrderItemRequest(PRODUCT_ID, List.of(LARGE_CUP, LESS_ICE), 1)));

        assertEquals(ErrorCode.SPEC_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("甜度"));
    }

    @Test
    @DisplayName("单选组选了 2 项（中杯+大杯）抛 1003")
    void twoOptionsInSingleSelectGroupThrows1003() {
        givenProductOnSale();
        givenAllFourSpecGroups();
        givenOptionsFromSeed();

        BusinessException ex = assertThrows(BusinessException.class, () -> pricingService.calculatePrice(
                new OrderItemRequest(PRODUCT_ID, List.of(MID_CUP, LARGE_CUP, LESS_ICE, HALF_SUGAR), 1)));

        assertEquals(ErrorCode.SPEC_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("杯型"));
    }

    @Test
    @DisplayName("越组选项（商品未挂加料组却选珍珠）抛 1003")
    void optionNotBelongToProductThrows1003() {
        givenProductOnSale();
        givenSpecGroups(CUP_SIZE_GROUP, TEMPERATURE_GROUP, SWEETNESS_GROUP);
        givenOptionsFromSeed();

        BusinessException ex = assertThrows(BusinessException.class, () -> pricingService.calculatePrice(
                new OrderItemRequest(PRODUCT_ID, List.of(MID_CUP, LESS_ICE, HALF_SUGAR, PEARL), 1)));

        assertEquals(ErrorCode.SPEC_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("不属于该商品"));
    }

    @Test
    @DisplayName("已停用的规格项抛 1003")
    void disabledOptionThrows1003() {
        givenProductOnSale();
        givenAllFourSpecGroups();
        givenOptionsFromSeed();

        BusinessException ex = assertThrows(BusinessException.class, () -> pricingService.calculatePrice(
                new OrderItemRequest(PRODUCT_ID, List.of(MID_CUP, LESS_ICE, HALF_SUGAR, DISABLED_PUDDING), 1)));

        assertEquals(ErrorCode.SPEC_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("已停用"));
    }

    @Test
    @DisplayName("同一规格项重复选择抛 1003")
    void duplicatedOptionThrows1003() {
        givenProductOnSale();
        givenAllFourSpecGroups();

        BusinessException ex = assertThrows(BusinessException.class, () -> pricingService.calculatePrice(
                new OrderItemRequest(PRODUCT_ID, List.of(MID_CUP, LESS_ICE, HALF_SUGAR, PEARL, PEARL), 1)));

        assertEquals(ErrorCode.SPEC_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("重复"));
    }

    @Test
    @DisplayName("下架商品抛 1002")
    void offShelfProductThrows1002() {
        when(productMapper.selectById(99L)).thenReturn(product(99L, "季节限定", "18.00", Product.STATUS_OFF));

        BusinessException ex = assertThrows(BusinessException.class, () -> pricingService.calculatePrice(
                new OrderItemRequest(99L, List.of(MID_CUP), 1)));

        assertEquals(ErrorCode.PRODUCT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("商品不存在抛 1002")
    void missingProductThrows1002() {
        when(productMapper.selectById(404L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> pricingService.calculatePrice(
                new OrderItemRequest(404L, List.of(MID_CUP), 1)));

        assertEquals(ErrorCode.PRODUCT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("quantity 边界：1 与 20 合法，0 / 21 / null 抛 1001")
    void quantityBoundary() {
        givenProductOnSale();
        givenAllFourSpecGroups();
        givenOptionsFromSeed();

        assertMoney("12.00", pricingService.calculatePrice(
                new OrderItemRequest(PRODUCT_ID, List.of(MID_CUP, LESS_ICE, HALF_SUGAR), 1)).getItemAmount());
        assertMoney("240.00", pricingService.calculatePrice(
                new OrderItemRequest(PRODUCT_ID, List.of(MID_CUP, LESS_ICE, HALF_SUGAR), 20)).getItemAmount());

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), assertThrows(BusinessException.class, () ->
                pricingService.calculatePrice(
                        new OrderItemRequest(PRODUCT_ID, List.of(MID_CUP, LESS_ICE, HALF_SUGAR), 0))).getCode());
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), assertThrows(BusinessException.class, () ->
                pricingService.calculatePrice(
                        new OrderItemRequest(PRODUCT_ID, List.of(MID_CUP, LESS_ICE, HALF_SUGAR), 21))).getCode());
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), assertThrows(BusinessException.class, () ->
                pricingService.calculatePrice(
                        new OrderItemRequest(PRODUCT_ID, List.of(MID_CUP, LESS_ICE, HALF_SUGAR), null))).getCode());
    }

    @Test
    @DisplayName("缺少 productId 或下单项为空抛 1001")
    void missingProductIdOrEmptyItemsThrows1001() {
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), assertThrows(BusinessException.class, () ->
                pricingService.calculatePrice(new OrderItemRequest(null, List.of(), 1))).getCode());
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), assertThrows(BusinessException.class, () ->
                pricingService.calculatePrice((OrderItemRequest) null)).getCode());
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), assertThrows(BusinessException.class, () ->
                pricingService.calculatePrice(List.of())).getCode());
    }

    // ---------- stub ----------

    private void givenProductOnSale() {
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product(PRODUCT_ID, "珍珠奶茶", "12.00", Product.STATUS_ON));
    }

    private void givenAllFourSpecGroups() {
        givenSpecGroups(CUP_SIZE_GROUP, TEMPERATURE_GROUP, SWEETNESS_GROUP, TOPPING_GROUP);
    }

    private void givenSpecGroups(Long... groupIds) {
        List<ProductSpecGroup> relations = new ArrayList<>();
        for (Long groupId : groupIds) {
            relations.add(relation(PRODUCT_ID, groupId));
        }
        when(productSpecGroupMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<ProductSpecGroup>>any()))
                .thenReturn(relations);
        when(specGroupMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<SpecGroup>>any()))
                .thenReturn(Stream.of(groupIds).map(PricingServiceTest::groupOf).toList());
    }

    /** 模拟按 id 批量取规格项：返回种子数据中命中的那些。 */
    private void givenOptionsFromSeed() {
        List<SpecOption> seed = List.of(
                option(MID_CUP, CUP_SIZE_GROUP, "中杯", 0.00, 1, 1),
                option(LARGE_CUP, CUP_SIZE_GROUP, "大杯", 3.00, 1, 2),
                option(3L, TEMPERATURE_GROUP, "正常冰", 0.00, 1, 1),
                option(LESS_ICE, TEMPERATURE_GROUP, "少冰", 0.00, 1, 2),
                option(8L, SWEETNESS_GROUP, "全糖", 0.00, 1, 1),
                option(HALF_SUGAR, SWEETNESS_GROUP, "半糖", 0.00, 1, 2),
                option(PEARL, TOPPING_GROUP, "珍珠", 2.00, 1, 1),
                option(COCONUT, TOPPING_GROUP, "椰果", 2.00, 1, 2),
                option(DISABLED_PUDDING, TOPPING_GROUP, "布丁", 3.00, 0, 3));
        when(specOptionMapper.selectBatchIds(ArgumentMatchers.<Collection<Long>>any()))
                .thenAnswer(invocation -> {
                    Collection<Long> ids = invocation.getArgument(0);
                    return seed.stream().filter(o -> ids.contains(o.getId())).toList();
                });
    }

    // ---------- helper ----------

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "期望 " + expected + " 实际 " + actual);
    }

    private static Product product(Long id, String name, String price, int status) {
        Product product = new Product();
        product.setId(id);
        product.setCategoryId(1L);
        product.setName(name);
        product.setBasePrice(new BigDecimal(price));
        product.setStatus(status);
        product.setSortOrder(1);
        return product;
    }

    private static SpecGroup groupOf(Long id) {
        return switch (id.intValue()) {
            case 1 -> specGroup(CUP_SIZE_GROUP, "CUP_SIZE", "杯型", 0, 1);
            case 2 -> specGroup(TEMPERATURE_GROUP, "TEMPERATURE", "温度", 0, 2);
            case 3 -> specGroup(SWEETNESS_GROUP, "SWEETNESS", "甜度", 0, 3);
            default -> specGroup(TOPPING_GROUP, "TOPPING", "加料", 1, 4);
        };
    }

    private static SpecGroup specGroup(Long id, String code, String name, int multiSelect, int sortOrder) {
        SpecGroup group = new SpecGroup();
        group.setId(id);
        group.setCode(code);
        group.setName(name);
        group.setMultiSelect(multiSelect);
        group.setEnabled(1);
        group.setSortOrder(sortOrder);
        return group;
    }

    private static SpecOption option(Long id, Long groupId, String name, double priceDelta, int enabled, int sortOrder) {
        SpecOption option = new SpecOption();
        option.setId(id);
        option.setGroupId(groupId);
        option.setName(name);
        option.setPriceDelta(BigDecimal.valueOf(priceDelta));
        option.setEnabled(enabled);
        option.setSortOrder(sortOrder);
        return option;
    }

    private static ProductSpecGroup relation(Long productId, Long groupId) {
        ProductSpecGroup relation = new ProductSpecGroup();
        relation.setId(groupId);
        relation.setProductId(productId);
        relation.setGroupId(groupId);
        return relation;
    }
}
