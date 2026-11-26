package com.milktea.order.ai.decision;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 菜单的英文标签字典：把商品名与规格选项名映射成英文标签，供 Jev 的 state 使用。
 *
 * <h2>为什么要做这件事</h2>
 * <p>Jev 官方明确声明「英文是主要训练语言、准确率最佳；CJK 可处理但精度不均」。
 * 而本店的商品名（珍珠奶茶）与规格名（少冰）全是中文。若不处理，
 * 「少冰 vs 去冰」「珍珠 vs 椰果」这类<b>细粒度区分</b>正是精度最不稳的地方——
 * 而这恰恰是点单错误率的主要来源。</p>
 *
 * <h2>关键设计：中文只出现在描述位，决策键一律 ASCII</h2>
 * <p>即便字典没有命中，模型也<b>不需要输出中文</b>——它返回的是 {@code P03}、{@code 2}
 * 这类 ASCII 编码，由后端映射回中文名。也就是说，语言精度的风险被限制在「理解输入」，
 * 不会扩散到「输出解析」。</p>
 *
 * <h2>未命中怎么办：降级而不是编造</h2>
 * <p>店主随时可能新增商品或规格，字典不可能总是全的。未命中时<b>回落到原始中文名</b>并置
 * {@link MenuState#degraded()} 为 true、打一条 WARN——比塞一个 {@code option_7} 这样的
 * 无意义占位符诚实得多（占位符会让模型在几个看不懂的选项之间瞎选）。</p>
 *
 * <p>升级路径（已在计划中标注）：真实门店扩容后，可在 {@code product} / {@code spec_option}
 * 表加一列 {@code label_en} 由店主自己填，本类改为优先读该列即可，调用方零改动。</p>
 */
@Component
public class MenuLabelDictionary {

    /** 字典键分隔符：规格组 code 与中文选项名的组合键。 */
    private static final String SEP = "|";

    /**
     * 规格选项英文标签（键为 {@code 组code|中文名}）。
     *
     * <p>覆盖 V2 种子数据的全部选项；这些名字是系统预设的四组规格模板，
     * 变化频率极低，作为内置字典是合适的。</p>
     */
    private static final Map<String, String> SPEC_LABELS = Map.ofEntries(
            Map.entry("CUP_SIZE" + SEP + "中杯", "Regular"),
            Map.entry("CUP_SIZE" + SEP + "大杯", "Large"),
            Map.entry("TEMPERATURE" + SEP + "正常冰", "Regular ice"),
            Map.entry("TEMPERATURE" + SEP + "少冰", "Less ice"),
            Map.entry("TEMPERATURE" + SEP + "去冰", "No ice"),
            Map.entry("TEMPERATURE" + SEP + "常温", "Room temperature"),
            Map.entry("TEMPERATURE" + SEP + "热饮", "Hot"),
            Map.entry("SWEETNESS" + SEP + "全糖", "Full sugar"),
            Map.entry("SWEETNESS" + SEP + "七分糖", "70% sugar"),
            Map.entry("SWEETNESS" + SEP + "半糖", "Half sugar"),
            Map.entry("SWEETNESS" + SEP + "三分糖", "30% sugar"),
            Map.entry("SWEETNESS" + SEP + "无糖", "No sugar"),
            Map.entry("SWEETNESS" + SEP + "一分糖", "10% sugar"),
            Map.entry("TOPPING" + SEP + "珍珠", "Pearl"),
            Map.entry("TOPPING" + SEP + "波霸", "Boba"),
            Map.entry("TOPPING" + SEP + "椰果", "Coconut jelly"),
            Map.entry("TOPPING" + SEP + "布丁", "Pudding"),
            Map.entry("TOPPING" + SEP + "芋泥", "Taro paste"),
            Map.entry("TOPPING" + SEP + "红豆", "Red bean"),
            Map.entry("TOPPING" + SEP + "仙草", "Grass jelly"),
            Map.entry("TOPPING" + SEP + "奶盖", "Milk foam"));

    /** 商品英文标签（键为中文商品名）。 */
    private static final Map<String, String> PRODUCT_LABELS = Map.ofEntries(
            Map.entry("珍珠奶茶", "Pearl milk tea"),
            Map.entry("波霸奶茶", "Boba milk tea"),
            Map.entry("芒果绿茶", "Mango green tea"),
            Map.entry("百香果柠檬茶", "Passion fruit lemon tea"),
            Map.entry("燕麦拿铁", "Oat milk latte"),
            Map.entry("芒果冰沙", "Mango smoothie"),
            Map.entry("四季春茶", "Four seasons oolong tea"),
            Map.entry("茉莉绿茶", "Jasmine green tea"),
            Map.entry("柠檬水", "Lemonade"),
            Map.entry("美式咖啡", "Americano"),
            Map.entry("拿铁", "Latte"));

    /**
     * 取规格选项的英文标签。
     *
     * @param groupCode  规格组 code（如 {@code SWEETNESS}）
     * @param optionName 中文选项名（如 {@code 少冰}）
     * @return 英文标签；字典未命中返回 {@code null}（由调用方回落中文并置降级标记）
     */
    public String specLabel(String groupCode, String optionName) {
        if (groupCode == null || optionName == null) {
            return null;
        }
        return SPEC_LABELS.get(groupCode + SEP + optionName);
    }

    /**
     * 取商品的英文标签。
     *
     * @param productName 中文商品名
     * @return 英文标签；未命中返回 {@code null}
     */
    public String productLabel(String productName) {
        if (productName == null) {
            return null;
        }
        return PRODUCT_LABELS.get(productName);
    }
}
