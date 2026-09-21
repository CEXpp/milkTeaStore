package com.milktea.order.ai.tools;

import com.milktea.order.product.service.MenuService;
import com.milktea.order.product.vo.ProductVo;
import com.milktea.order.product.vo.SpecGroupVo;
import com.milktea.order.product.vo.SpecOptionVo;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * AI 工具 · 查菜单（T30，LLD 6.3 第一个工具）。
 *
 * <p>数据来源复用顾客端菜单读链路 {@link MenuService#searchOnSaleProducts(String)}：
 * 只读在售商品与启用中的规格项，因此下架商品、停用规格对模型不可见——这是
 * SRS 约束三原则「只能点真实存在的商品」在后端的落地层，模型编造不出菜单里没有的东西。</p>
 *
 * <p><b>工具只描述数据、不生成数据</b>：本类不含任何价格计算，价差直接取自规格表读链路。</p>
 */
@Component
@RequiredArgsConstructor
public class MenuSearchTool {

    private final MenuService menuService;

    /**
     * 查询当前在售菜单（LLD 6.3 签名逐字）。
     *
     * @param keyword 搜索关键词，可空；空则返回全部在售商品
     * @return 供模型阅读的菜单文本（商品名 + 基础价 + 可选规格与价差）
     */
    @Tool("查询当前在售菜单：商品名、价格、可选杯型温度甜度加料。找商品必用。")
    public String searchMenu(@P(value = "搜索关键词，可空", required = false) String keyword) {
        List<ProductVo> products = menuService.searchOnSaleProducts(keyword);
        if (products.isEmpty()) {
            return StringUtils.hasText(keyword)
                    ? "菜单中没有找到与「" + keyword.trim() + "」相关的商品。"
                    : "当前门店没有在售商品。";
        }
        StringBuilder text = new StringBuilder("在售商品共 " + products.size() + " 项：\n");
        for (ProductVo product : products) {
            text.append("- ").append(product.getName())
                    .append(" 基础价 ").append(product.getBasePrice()).append(" 元");
            appendSpecGroups(text, product.getSpecGroups());
            text.append('\n');
        }
        return text.toString();
    }

    /**
     * 追加商品的可选规格：{@code 组名(选1/可多选): 选项A/选项B+2.00}。
     *
     * <p>单选组标注「选1」，提示模型该组必须选；多选组标注「可多选」。</p>
     */
    private void appendSpecGroups(StringBuilder text, List<SpecGroupVo> groups) {
        if (groups == null || groups.isEmpty()) {
            return;
        }
        text.append(" ｜ 规格：");
        for (int i = 0; i < groups.size(); i++) {
            SpecGroupVo group = groups.get(i);
            if (i > 0) {
                text.append(" ｜ ");
            }
            text.append(group.getName())
                    .append(Boolean.TRUE.equals(group.getMultiSelect()) ? "(可多选): " : "(选1): ");
            List<SpecOptionVo> options = group.getOptions() == null ? List.of() : group.getOptions();
            for (int j = 0; j < options.size(); j++) {
                if (j > 0) {
                    text.append('/');
                }
                SpecOptionVo option = options.get(j);
                text.append(option.getName()).append(priceDeltaSuffix(option.getPriceDelta()));
            }
        }
    }

    /**
     * 价差后缀：0.00 不显示；正数补 {@code +}，负数自带 {@code -}。
     *
     * @param priceDelta 两位小数字符串（{@code MoneyUtils.format} 口径）
     */
    private String priceDeltaSuffix(String priceDelta) {
        if (priceDelta == null || priceDelta.startsWith("0.00") || priceDelta.startsWith("-0.00")) {
            return "";
        }
        return priceDelta.startsWith("-") ? priceDelta : "+" + priceDelta;
    }
}
