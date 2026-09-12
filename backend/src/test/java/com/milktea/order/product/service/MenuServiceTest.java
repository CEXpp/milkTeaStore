package com.milktea.order.product.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.milktea.order.product.entity.Category;
import com.milktea.order.product.entity.Product;
import com.milktea.order.product.entity.ProductSpecGroup;
import com.milktea.order.product.entity.SpecGroup;
import com.milktea.order.product.entity.SpecOption;
import com.milktea.order.product.mapper.CategoryMapper;
import com.milktea.order.product.mapper.ProductMapper;
import com.milktea.order.product.mapper.ProductSpecGroupMapper;
import com.milktea.order.product.mapper.SpecGroupMapper;
import com.milktea.order.product.mapper.SpecOptionMapper;
import com.milktea.order.product.vo.MenuVo;
import com.milktea.order.product.vo.ProductVo;
import com.milktea.order.product.vo.SpecGroupVo;
import com.milktea.order.shop.entity.ShopConfig;
import com.milktea.order.shop.mapper.ShopConfigMapper;
import com.milktea.order.shop.vo.ShopStatusVo;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 菜单读链路单元测试（不依赖数据库）。
 */
@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private SpecGroupMapper specGroupMapper;
    @Mock
    private SpecOptionMapper specOptionMapper;
    @Mock
    private ProductSpecGroupMapper productSpecGroupMapper;
    @Mock
    private ShopConfigMapper shopConfigMapper;

    @InjectMocks
    private MenuService menuService;

    /**
     * 单测无 MyBatis 上下文，需手动初始化实体元数据，否则 LambdaQueryWrapper 无法解析列。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        Stream.of(Category.class, Product.class, SpecGroup.class, SpecOption.class,
                        ProductSpecGroup.class, ShopConfig.class)
                .forEach(clazz -> TableInfoHelper.initTableInfo(assistant, clazz));
    }

    @Test
    @DisplayName("菜单返回 4 分类 4 商品，且每个商品挂 4 组规格")
    void menuReturnsFourCategoriesFourProductsWithSpecGroups() {
        when(categoryMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Category>>any()))
                .thenReturn(List.of(category(1L, "经典奶茶", 1), category(2L, "果茶", 2),
                        category(3L, "咖啡", 3), category(4L, "冰沙", 4)));
        when(productMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Product>>any()))
                .thenReturn(List.of(product(10L, 1L, "珍珠奶茶", "12.00", 12.00),
                        product(11L, 2L, "芒果绿茶", "15.00", 15.00),
                        product(12L, 3L, "燕麦拿铁", "16.00", 16.00),
                        product(13L, 4L, "芒果冰沙", "15.00", 15.00)));
        when(shopConfigMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<ShopConfig>>any()))
                .thenReturn(List.of(config("paused", "false")));
        when(productSpecGroupMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<ProductSpecGroup>>any()))
                .thenReturn(List.of(
                        relation(10L, 1L), relation(10L, 2L), relation(10L, 3L), relation(10L, 4L),
                        relation(11L, 1L), relation(11L, 2L), relation(11L, 3L), relation(11L, 4L),
                        relation(12L, 1L), relation(12L, 2L), relation(12L, 3L), relation(12L, 4L),
                        relation(13L, 1L), relation(13L, 2L), relation(13L, 3L), relation(13L, 4L)));
        when(specGroupMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<SpecGroup>>any()))
                .thenReturn(List.of(specGroup(1L, "CUP_SIZE", "杯型", 0),
                        specGroup(2L, "TEMPERATURE", "温度", 0),
                        specGroup(3L, "SWEETNESS", "甜度", 0),
                        specGroup(4L, "TOPPING", "加料", 1)));
        when(specOptionMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<SpecOption>>any()))
                .thenReturn(List.of(
                        specOption(1L, 1L, "中杯", 0.00), specOption(2L, 1L, "大杯", 3.00),
                        specOption(3L, 2L, "冰", 0.00), specOption(4L, 2L, "少冰", 0.00), specOption(5L, 2L, "热", 0.00),
                        specOption(8L, 3L, "全糖", 0.00), specOption(9L, 3L, "半糖", 0.00),
                        specOption(13L, 4L, "珍珠", 2.00), specOption(14L, 4L, "椰果", 2.00)));

        MenuVo menu = menuService.getMenu();

        assertFalse(menu.getPaused());
        assertEquals(4, menu.getCategories().size());
        assertEquals(List.of(1L, 2L, 3L, 4L), menu.getCategories().stream().map(c -> c.getId()).toList());
        assertEquals(4, menu.getCategories().stream().mapToLong(c -> c.getProducts().size()).sum());

        ProductVo pearl = menu.getCategories().get(0).getProducts().get(0);
        assertEquals("珍珠奶茶", pearl.getName());
        assertEquals("/api/files/menu/2026/09/pearl-milk-tea.jpg", pearl.getImageUrl());
        assertEquals(4, pearl.getSpecGroups().size());
        assertEquals(List.of("CUP_SIZE", "TEMPERATURE", "SWEETNESS", "TOPPING"),
                pearl.getSpecGroups().stream().map(SpecGroupVo::getCode).toList());

        SpecGroupVo cupSize = pearl.getSpecGroups().get(0);
        assertFalse(cupSize.getMultiSelect());
        assertEquals(2, cupSize.getOptions().size());
        assertEquals("3.00", cupSize.getOptions().get(1).getPriceDelta());
        assertTrue(pearl.getSpecGroups().get(3).getMultiSelect());
    }

    @Test
    @DisplayName("下架商品不出现在菜单（查询带 status=1 条件）")
    void offShelfProductHidden() {
        List<Product> stored = List.of(product(10L, 1L, "珍珠奶茶", "12.00", 12.00),
                offShelfProduct(99L, 1L, "季节限定"));
        when(categoryMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Category>>any()))
                .thenReturn(List.of(category(1L, "经典奶茶", 1)));
        when(shopConfigMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<ShopConfig>>any()))
                .thenReturn(List.of(config("paused", "false")));
        // 模拟数据库按 status=1 过滤
        when(productMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Product>>any()))
                .thenAnswer(invocation -> stored.stream()
                        .filter(p -> p.getStatus() != null && p.getStatus() == Product.STATUS_ON)
                        .toList());
        when(productSpecGroupMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<ProductSpecGroup>>any()))
                .thenReturn(List.of());

        MenuVo menu = menuService.getMenu();

        assertEquals(1, menu.getCategories().get(0).getProducts().size());
        assertTrue(menu.getCategories().stream()
                .flatMap(c -> c.getProducts().stream())
                .noneMatch(p -> "季节限定".equals(p.getName())));

        ArgumentCaptor<LambdaQueryWrapper<Product>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(productMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("status"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(Product.STATUS_ON));
    }

    @Test
    @DisplayName("价格均为两位小数字符串")
    void priceFormattedWithTwoDecimals() {
        when(categoryMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Category>>any()))
                .thenReturn(List.of(category(1L, "经典奶茶", 1)));
        when(shopConfigMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<ShopConfig>>any()))
                .thenReturn(List.of(config("paused", "false")));
        when(productMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Product>>any()))
                .thenReturn(List.of(product(10L, 1L, "珍珠奶茶", "12.00", 12.0)));
        when(productSpecGroupMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<ProductSpecGroup>>any()))
                .thenReturn(List.of(relation(10L, 1L)));
        when(specGroupMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<SpecGroup>>any()))
                .thenReturn(List.of(specGroup(1L, "CUP_SIZE", "杯型", 0)));
        when(specOptionMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<SpecOption>>any()))
                .thenReturn(List.of(specOption(1L, 1L, "中杯", 0), specOption(2L, 1L, "大杯", 3)));

        MenuVo menu = menuService.getMenu();

        ProductVo pearl = menu.getCategories().get(0).getProducts().get(0);
        assertEquals("12.00", pearl.getBasePrice());
        assertEquals(List.of("0.00", "3.00"), pearl.getSpecGroups().get(0).getOptions().stream()
                .map(o -> o.getPriceDelta()).toList());
    }

    @Test
    @DisplayName("无图片 key 时 imageUrl 为 null")
    void imageUrlNullWhenNoImageKey() {
        Product product = product(10L, 1L, "珍珠奶茶", "12.00", 12.00);
        product.setImageKey(null);
        when(categoryMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Category>>any()))
                .thenReturn(List.of(category(1L, "经典奶茶", 1)));
        when(shopConfigMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<ShopConfig>>any()))
                .thenReturn(List.of(config("paused", "false")));
        when(productMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Product>>any()))
                .thenReturn(List.of(product));
        when(productSpecGroupMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<ProductSpecGroup>>any()))
                .thenReturn(List.of());

        MenuVo menu = menuService.getMenu();

        assertNull(menu.getCategories().get(0).getProducts().get(0).getImageUrl());
    }

    @Test
    @DisplayName("门店状态读 shop_config 的 paused / notice")
    void shopStatusReadsShopConfig() {
        when(shopConfigMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<ShopConfig>>any()))
                .thenReturn(List.of(config("paused", "true"), config("notice", "高峰期制作中，稍后开放点单")));

        ShopStatusVo status = menuService.getShopStatus();

        assertTrue(status.getPaused());
        assertEquals("高峰期制作中，稍后开放点单", status.getNotice());
    }

    private Category category(Long id, String name, int sortOrder) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        category.setSortOrder(sortOrder);
        category.setCreatedAt(LocalDateTime.now());
        return category;
    }

    private Product product(Long id, Long categoryId, String name, String ignored, double price) {
        Product product = new Product();
        product.setId(id);
        product.setCategoryId(categoryId);
        product.setName(name);
        product.setDescription("描述");
        product.setImageKey("menu/2026/09/pearl-milk-tea.jpg");
        product.setBasePrice(BigDecimal.valueOf(price));
        product.setStatus(Product.STATUS_ON);
        product.setSortOrder(1);
        return product;
    }

    private Product offShelfProduct(Long id, Long categoryId, String name) {
        Product product = product(id, categoryId, name, "0.00", 0);
        product.setStatus(Product.STATUS_OFF);
        return product;
    }

    private SpecGroup specGroup(Long id, String code, String name, int multiSelect) {
        SpecGroup group = new SpecGroup();
        group.setId(id);
        group.setCode(code);
        group.setName(name);
        group.setMultiSelect(multiSelect);
        group.setEnabled(1);
        group.setSortOrder(Math.toIntExact(id));
        return group;
    }

    private SpecOption specOption(Long id, Long groupId, String name, double priceDelta) {
        SpecOption option = new SpecOption();
        option.setId(id);
        option.setGroupId(groupId);
        option.setName(name);
        option.setPriceDelta(BigDecimal.valueOf(priceDelta));
        option.setEnabled(1);
        option.setSortOrder(Math.toIntExact(id));
        return option;
    }

    private ProductSpecGroup relation(Long productId, Long groupId) {
        ProductSpecGroup relation = new ProductSpecGroup();
        relation.setProductId(productId);
        relation.setGroupId(groupId);
        return relation;
    }

    private ShopConfig config(String key, String value) {
        ShopConfig config = new ShopConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        return config;
    }
}
