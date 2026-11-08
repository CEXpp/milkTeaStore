package com.milktea.order.ai.decision;

import com.milktea.order.ai.draft.DraftItem;
import com.milktea.order.product.service.MenuService;
import com.milktea.order.product.vo.ProductVo;
import com.milktea.order.product.vo.SpecGroupVo;
import com.milktea.order.product.vo.SpecOptionVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 决策上下文组装测试。
 *
 * <p><b>本测试守的是「语言纪律」这条防线</b>：Jev 官方声明英文准确率最佳、CJK 精度不均，
 * 因此菜单段与偏好段必须零中日韩字符（商品与规格全部走英文标签字典）。
 * 一旦有人往题面里塞了中文，这里会立刻拦下——这是「点错杯」风险的第一道闸门。</p>
 *
 * <p>另一条守的是<b>下标与菜单顺序的一致性</b>：模型返回的是下标，落草稿靠下标反查中文名，
 * 顺序错一位就是另一杯。故下标一律断言为具体数字，而不是「不为空」。</p>
 */
@ExtendWith(MockitoExtension.class)
class MenuStateAssemblerTest {

    @Mock
    private MenuService menuService;

    private MenuStateAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new MenuStateAssembler(menuService, new MenuLabelDictionary(), defaultProperties());
    }

    @Test
    @DisplayName("全量在售菜单进入 state，商品编码按菜单顺序连续")
    void encodesAllOnSaleProducts() {
        when(menuService.searchOnSaleProducts(isNull())).thenReturn(List.of(
                product(1L, "珍珠奶茶", "12.00"),
                product(2L, "波霸奶茶", "13.00")));

        MenuState state = assembler.assemble("来一杯", PreferenceSnapshot.empty(), List.of());

        assertEquals(2, state.productByCode().size());
        assertTrue(state.hasProduct("P01"));
        assertTrue(state.hasProduct("P02"));
        assertEquals("珍珠奶茶", state.product("P01").name(), "编码必须能反查回中文名");
        assertTrue(state.menuBlock().contains("P01 | Pearl milk tea"), state.menuBlock());
        assertFalse(state.degraded(), "字典全覆盖时不应置降级标记");
    }

    @Test
    @DisplayName("菜单段与偏好段零 CJK 字符；中文只允许出现在顾客请求段")
    void keepsConstructedBlocksFreeOfCjk() {
        when(menuService.searchOnSaleProducts(isNull())).thenReturn(List.of(
                product(1L, "珍珠奶茶", "12.00"),
                product(2L, "燕麦拿铁", "16.00")));
        PreferenceSnapshot preference = new PreferenceSnapshot(
                Map.of("SWEETNESS", List.of("半糖"), "TEMPERATURE", List.of("少冰")),
                "珍珠奶茶", 7);

        MenuState state = assembler.assemble("帮我点一杯少糖的", preference, List.of());

        assertFalse(containsCjk(state.menuBlock()),
                "菜单段出现中文说明英文标签字典有缺口：" + state.menuBlock());
        assertFalse(containsCjk(state.preferenceBlock()),
                "偏好段出现中文：" + state.preferenceBlock());
        assertTrue(containsCjk(state.requestBlock()), "顾客原话应原样保留（允许中文）");
        assertTrue(state.requestBlock().contains("帮我点一杯少糖的"));
    }

    @Test
    @DisplayName("规格下标与菜单顺序严格一致，可双向反查")
    void keepsOptionIndexAlignedWithMenuOrder() {
        when(menuService.searchOnSaleProducts(isNull())).thenReturn(List.of(product(1L, "珍珠奶茶", "12.00")));

        MenuState state = assembler.assemble("来一杯", PreferenceSnapshot.empty(), List.of());

        // V2 种子顺序：全糖0 七分糖1 半糖2 三分糖3 无糖4
        assertEquals(2, state.optionIndexOf("SWEETNESS", "半糖"));
        assertEquals("半糖", state.optionNameOf("SWEETNESS", 2));
        // 温度：正常冰0 少冰1 去冰2 常温3 热饮4
        assertEquals(1, state.optionIndexOf("TEMPERATURE", "少冰"));
        // 杯型：中杯0 大杯1（大杯 +3.00）
        assertEquals(1, state.optionIndexOf("CUP_SIZE", "大杯"));
        assertEquals("3.00", state.groupByCode().get("CUP_SIZE").at(1).priceDelta());
        assertTrue(state.menuBlock().contains("CUP_SIZE (single): index 0 = Regular; index 1 = Large (+3.00);"),
                state.menuBlock());
        assertTrue(state.groupByCode().get("TOPPING").multiSelect(), "加料组必须标记为多选");
    }

    @Test
    @DisplayName("无历史不注入任何默认值；有历史则按下标注入且全英文")
    void injectsPreferenceOnlyWhenPresent() {
        when(menuService.searchOnSaleProducts(isNull())).thenReturn(List.of(
                product(1L, "珍珠奶茶", "12.00"),
                product(2L, "波霸奶茶", "13.00")));

        MenuState empty = assembler.assemble("来一杯", PreferenceSnapshot.empty(), List.of());
        assertTrue(empty.preferenceBlock().isEmpty(), "新客不应被塞任何默认偏好");

        PreferenceSnapshot preference = new PreferenceSnapshot(
                Map.of("SWEETNESS", List.of("半糖"), "TOPPING", List.of("珍珠")),
                "波霸奶茶", 7);
        MenuState learned = assembler.assemble("来一杯", preference, List.of());

        assertTrue(learned.preferenceBlock().contains("favorite_product=P02"), learned.preferenceBlock());
        assertTrue(learned.preferenceBlock().contains("SWEETNESS=2"), learned.preferenceBlock());
        assertTrue(learned.preferenceBlock().contains("TOPPING=0"), learned.preferenceBlock());
        assertTrue(learned.preferenceBlock().contains("from 7 past paid orders"), learned.preferenceBlock());
    }

    @Test
    @DisplayName("草稿按编码入 state；已下架商品的草稿条目静默略过")
    void encodesDraftAndSkipsOffShelfItems() {
        when(menuService.searchOnSaleProducts(isNull())).thenReturn(List.of(
                product(1L, "珍珠奶茶", "12.00"),
                product(2L, "波霸奶茶", "13.00")));

        List<DraftItem> draft = List.of(
                new DraftItem("珍珠奶茶", List.of("大杯", "半糖"), 2),
                new DraftItem("已下架的老商品", List.of("大杯"), 1));

        MenuState state = assembler.assemble("再加一杯", PreferenceSnapshot.empty(), draft);

        assertTrue(state.menuBlock().contains("CURRENT DRAFT"), state.menuBlock());
        assertTrue(state.menuBlock().contains("P01{CUP_SIZE:1,SWEETNESS:2}x2"), state.menuBlock());
        assertFalse(state.menuBlock().contains("已下架的老商品"), "下架商品不为它编造编码");
    }

    @Test
    @DisplayName("空草稿不产生草稿段；空菜单给出显式空态")
    void handlesEmptyDraftAndEmptyMenu() {
        when(menuService.searchOnSaleProducts(isNull())).thenReturn(List.of());

        MenuState state = assembler.assemble("来一杯", PreferenceSnapshot.empty(), List.of());

        assertFalse(state.menuBlock().contains("CURRENT DRAFT"));
        assertTrue(state.menuBlock().contains("(no on-sale product)"), state.menuBlock());
        assertNull(state.product("P01"));
        assertFalse(state.hasProduct("P01"));
    }

    @Test
    @DisplayName("state 超出预算时按菜单序截断尾商品，且至少保留一个候选")
    void truncatesTailProductsWhenOverBudget() {
        List<String> knownNames = List.of("珍珠奶茶", "波霸奶茶", "芒果绿茶", "百香果柠檬茶", "燕麦拿铁",
                "芒果冰沙", "四季春茶", "茉莉绿茶", "柠檬水", "美式咖啡", "拿铁");
        List<ProductVo> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(product((long) i + 1, knownNames.get(i % knownNames.size()), "12.00"));
        }
        when(menuService.searchOnSaleProducts(isNull())).thenReturn(many);
        // maxStateTokens 取下限 256，40 个商品必然超预算
        JevProperties tiny = new JevProperties(true, "http://x", "k", "/v1/systemone", "jev-1.13.0",
                1, 1, 256, 0.72, 1, 100, 1, 60);
        MenuStateAssembler truncating = new MenuStateAssembler(menuService, new MenuLabelDictionary(), tiny);

        MenuState state = truncating.assemble("来一杯", PreferenceSnapshot.empty(), List.of());

        assertTrue(state.productByCode().size() < many.size(), "应发生截断");
        assertTrue(state.productByCode().size() >= 1, "至少保留一个候选，否则模型无从选择");
        assertTrue(state.hasProduct("P01"), "保留的必须是菜单靠前的商品");
        assertFalse(state.degraded(), "截断不应被误记为标签字典降级");
    }

    @Test
    @DisplayName("字典未命中时回落中文并显式降级（不编造占位符）")
    void fallsBackToChineseAndFlagsDegraded() {
        when(menuService.searchOnSaleProducts(isNull())).thenReturn(List.of(product(1L, "新上市的怪名字", "18.00")));

        MenuState state = assembler.assemble("来一杯", PreferenceSnapshot.empty(), List.of());

        assertTrue(state.degraded(), "字典未命中必须置降级标记");
        assertTrue(state.menuBlock().contains("新上市的怪名字"),
                "未命中时回落到真实中文名，而不是 option_7 这类占位符");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private JevProperties defaultProperties() {
        return new JevProperties(true, "http://127.0.0.1:1", "test-key", "/v1/systemone", "jev-1.13.0",
                2, 3, 6000, 0.72, 3, 1000, 5, 60);
    }

    private ProductVo product(Long id, String name, String basePrice) {
        ProductVo vo = new ProductVo();
        vo.setId(id);
        vo.setName(name);
        vo.setBasePrice(basePrice);
        vo.setSpecGroups(List.of(cupSize(), temperature(), sweetness(), topping()));
        return vo;
    }

    private SpecGroupVo cupSize() {
        return group("CUP_SIZE", "杯型", false,
                option(1L, "中杯", "0.00"), option(2L, "大杯", "3.00"));
    }

    private SpecGroupVo temperature() {
        return group("TEMPERATURE", "温度", false,
                option(3L, "正常冰", "0.00"), option(4L, "少冰", "0.00"), option(5L, "去冰", "0.00"),
                option(6L, "常温", "0.00"), option(7L, "热饮", "0.00"));
    }

    private SpecGroupVo sweetness() {
        return group("SWEETNESS", "甜度", false,
                option(8L, "全糖", "0.00"), option(9L, "七分糖", "0.00"), option(10L, "半糖", "0.00"),
                option(11L, "三分糖", "0.00"), option(12L, "无糖", "0.00"));
    }

    private SpecGroupVo topping() {
        return group("TOPPING", "加料", true,
                option(13L, "珍珠", "2.00"), option(14L, "椰果", "2.00"));
    }

    private SpecGroupVo group(String code, String name, boolean multiSelect, SpecOptionVo... options) {
        SpecGroupVo vo = new SpecGroupVo();
        vo.setCode(code);
        vo.setName(name);
        vo.setMultiSelect(multiSelect);
        vo.setOptions(List.of(options));
        return vo;
    }

    private SpecOptionVo option(Long id, String name, String priceDelta) {
        SpecOptionVo vo = new SpecOptionVo();
        vo.setId(id);
        vo.setName(name);
        vo.setPriceDelta(priceDelta);
        return vo;
    }

    /** 与生产代码同口径的 CJK 判定（测试独立实现，避免「用被测代码证明被测代码」）。 */
    private boolean containsCjk(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF)) {
                return true;
            }
        }
        return false;
    }
}
