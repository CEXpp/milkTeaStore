package com.milktea.order.ai.decision;

import com.milktea.order.ai.draft.DraftItem;
import com.milktea.order.product.service.MenuService;
import com.milktea.order.product.vo.ProductVo;
import com.milktea.order.product.vo.SpecGroupVo;
import com.milktea.order.product.vo.SpecOptionVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把全量在售菜单 + 顾客偏好 + 顾客原话组装成 Jev 的 {@code state}。
 *
 * <h2>为什么是全量注入而不是检索</h2>
 * <p>RAG 解决的是「语料太大、捞不全」，而这里菜单只有少量商品与四组规格，是强结构化小数据。
 * 上向量检索不仅没必要，还会引入召回误差（漏召 → 模型看不到某个商品 → 推荐错）。
 * Jev 单请求 64k tokens 的上下文里，全量菜单连零头都占不到。</p>
 *
 * <h2>编码纪律</h2>
 * <ul>
 *   <li>商品编码 {@code P01..}、规格组用其英文 {@code code}、规格选项用<b>组内下标</b>；</li>
 *   <li>描述性文字优先用 {@link MenuLabelDictionary} 的英文标签；字典未命中才回落中文并置降级标记；</li>
 *   <li>顾客原话单独成段，不做改写——改写就丢失了原始语义（且我们不能替顾客转述需求）。</li>
 * </ul>
 *
 * <p><b>下标必须与菜单顺序严格一致</b>：模型返回的是下标，落草稿时按
 * {@code groupCode + index} 反查中文选项名。顺序错一位就会点成另一杯，
 * 因此下标直接取自 {@link MenuService} 同源读链路的返回顺序，不做任何重排。</p>
 *
 * <p>本类<b>不读会话</b>：草稿由调用方传入。这样它只依赖菜单读链路，
 * 单元测试可以完全脱离数据库与会话。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MenuStateAssembler {

    /** 规格组展开上限保护（真实只有四组，此处仅防御异常数据把 state 撑爆）。 */
    private static final int MAX_GROUPS = 12;

    private final MenuService menuService;
    private final MenuLabelDictionary labels;
    private final JevProperties properties;

    /**
     * 组装决策上下文。
     *
     * @param userMessage 顾客原话（进 {@code requestBlock}，是 state 中唯一允许中文的位置）
     * @param preference  偏好快照（可为 {@link PreferenceSnapshot#empty()}）
     * @param draft       当前草稿条目（可为空列表），让模型知道「已经点了什么」
     * @return 决策上下文与编码映射表
     */
    public MenuState assemble(String userMessage, PreferenceSnapshot preference, List<DraftItem> draft) {
        List<ProductVo> products = menuService.searchOnSaleProducts(null);

        Map<String, MenuState.SpecGroupRef> groupByCode = new LinkedHashMap<>();
        boolean[] degraded = {false};

        // 第一遍：先收齐规格组（全局模板），商品行才能引用到合法组 code
        for (ProductVo product : products) {
            for (SpecGroupVo group : safeGroups(product)) {
                registerGroup(group, groupByCode, degraded);
            }
        }

        MenuDraft menu = buildMenuDraft(products, groupByCode, degraded);
        String preferenceBlock = buildPreferenceBlock(preference, menu.productByCode(), groupByCode);
        String draftBlock = buildDraftBlock(draft, menu.productByCode(), groupByCode);
        String menuBlock = draftBlock.isBlank() ? menu.text() : menu.text() + "\n\n" + draftBlock;
        String requestBlock = "CUSTOMER REQUEST (verbatim, may be Chinese):\n"
                + (userMessage == null ? "" : userMessage);

        if (degraded[0]) {
            log.warn("[JEV] 标签字典存在未命中项，state 已回落中文：请在 MenuLabelDictionary 补齐英文标签"
                    + "（升级路径：product/spec_option 加 label_en 列后改读该列，调用方零改动）");
        }
        return new MenuState(menuBlock, preferenceBlock, requestBlock,
                menu.productByCode(), groupByCode, degraded[0]);
    }

    /** 登记一个规格组（同 code 只登记一次；选项下标即该组菜单顺序）。 */
    private void registerGroup(SpecGroupVo group,
                               Map<String, MenuState.SpecGroupRef> groupByCode,
                               boolean[] degraded) {
        String code = group.getCode();
        if (code == null || code.isBlank() || groupByCode.containsKey(code)) {
            return;
        }
        if (groupByCode.size() >= MAX_GROUPS) {
            // 与商品截断同样打 WARN：静默丢组会让商品行引用到不存在于规格段的悬空 code
            log.warn("[JEV] 规格组数量超过上限 {}，已忽略组 {}（避免 state 膨胀与悬空引用）", MAX_GROUPS, code);
            return;
        }
        List<SpecOptionVo> raw = group.getOptions() == null ? List.of() : group.getOptions();
        if (raw.isEmpty()) {
            // 「启用但选项全被停用」的组不能登记：它会让后续生成 criteria 为空的单选题，
            // 而空 criteria 若被对方判为 422，那是 fatal 且不重试 —— 一个数据问题会放大成
            // 「决策链对所有顾客整体降级」，直到店主修数据为止。
            log.warn("[JEV] 规格组 {} 没有任何启用中的选项，已从决策上下文中剔除", code);
            return;
        }
        List<MenuState.OptionRef> options = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            SpecOptionVo option = raw.get(i);
            String label = labels.specLabel(code, option.getName());
            if (label == null) {
                label = option.getName();
                degraded[0] = true;
            }
            options.add(new MenuState.OptionRef(i, option.getId(), option.getName(), label, option.getPriceDelta()));
        }
        groupByCode.put(code, new MenuState.SpecGroupRef(code, group.getName(),
                Boolean.TRUE.equals(group.getMultiSelect()), List.copyOf(options)));
    }

    /** 菜单段：商品行 + 规格展开行。受 {@code jev.max-state-tokens} 保护，超出时按菜单序截断尾部商品。 */
    private MenuDraft buildMenuDraft(List<ProductVo> products,
                                     Map<String, MenuState.SpecGroupRef> groupByCode,
                                     boolean[] degraded) {
        int budget = properties.maxStateTokensOrDefault();
        // 规格段先算出来并计入预算：只累计商品行的话，规格膨胀时会整体突破预算
        // （官方对「state + 最长单问」有硬限，撞上去是 422，而且一次失败对所有顾客生效）
        String specBlock = buildSpecBlock(groupByCode);
        int specTokens = JevTextUtils.estimateTokens(specBlock);
        if (specTokens >= budget) {
            log.warn("[JEV] 规格段已占满 state 预算（specTokens={} budget={}），商品段将只保留一个候选",
                    specTokens, budget);
        }
        Map<String, MenuState.ProductRef> productByCode = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder("MENU (all on-sale products, in menu order):\n");
        int used = JevTextUtils.estimateTokens(text.toString());
        boolean truncated = false;

        for (int i = 0; i < products.size(); i++) {
            ProductVo product = products.get(i);
            String code = String.format("P%02d", i + 1);
            List<String> groups = new ArrayList<>();
            for (SpecGroupVo group : safeGroups(product)) {
                if (group.getCode() != null && groupByCode.containsKey(group.getCode())) {
                    groups.add(group.getCode());
                }
            }
            String label = labels.productLabel(product.getName());
            if (label == null) {
                label = product.getName();
                degraded[0] = true;
            }
            String line = code + " | " + label + " | base_price=" + product.getBasePrice()
                    + " | spec_groups=" + (groups.isEmpty() ? "NONE" : String.join(",", groups)) + "\n";

            int lineTokens = JevTextUtils.estimateTokens(line);
            // 至少保留一个商品：全截空会让模型完全没有候选，比超预算更糟
            if (!productByCode.isEmpty() && used + lineTokens + specTokens > budget) {
                truncated = true;
                break;
            }
            text.append(line);
            used += lineTokens;
            productByCode.put(code, new MenuState.ProductRef(code, product.getName(), label,
                    product.getBasePrice(), List.copyOf(groups)));
        }
        if (truncated) {
            log.warn("[JEV] 菜单超出 state 预算，已截断尾部商品：budgetTokens={} kept={} total={}",
                    budget, productByCode.size(), products.size());
        }
        if (productByCode.isEmpty()) {
            text.append("(no on-sale product)\n");
        }

        text.append(specBlock);
        return new MenuDraft(text.toString(), productByCode);
    }

    /** 规格段：组 code + 组内下标 + 英文标签 + 价差。 */
    private String buildSpecBlock(Map<String, MenuState.SpecGroupRef> groupByCode) {
        StringBuilder spec = new StringBuilder(
                "\nSPEC GROUPS (answer with the option index; single = exactly one, multi = any number):\n");
        for (MenuState.SpecGroupRef group : groupByCode.values()) {
            spec.append(group.code()).append(group.multiSelect() ? " (multi):" : " (single):");
            for (MenuState.OptionRef option : group.options()) {
                spec.append(" index ").append(option.index()).append(" = ").append(option.label())
                        .append(priceSuffix(option.priceDelta())).append(';');
            }
            spec.append('\n');
        }
        return spec.toString();
    }

    /** 偏好段：全段英文；无历史时返回空串（新客不注入任何默认值）。 */
    private String buildPreferenceBlock(PreferenceSnapshot preference,
                                        Map<String, MenuState.ProductRef> productByCode,
                                        Map<String, MenuState.SpecGroupRef> groupByCode) {
        if (preference == null || preference.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        String favoriteCode = findCode(preference.favoriteProductName(), productByCode);
        if (favoriteCode != null) {
            parts.add("favorite_product=" + favoriteCode);
        }
        for (Map.Entry<String, MenuState.SpecGroupRef> entry : groupByCode.entrySet()) {
            List<Integer> indexes = new ArrayList<>();
            for (String name : preference.names(entry.getKey())) {
                int index = indexOf(entry.getValue(), name);
                if (index >= 0) {
                    indexes.add(index);
                }
            }
            if (!indexes.isEmpty()) {
                parts.add(entry.getKey() + "=" + join(indexes));
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return "LEARNED PREFERENCE of this customer (from " + preference.sampleOrders()
                + " past paid orders; use as default only, the request always wins):\n"
                + String.join("; ", parts);
    }

    /** 草稿段：用编码表达已点内容，让模型能区分「再来一杯」是追加还是覆盖。 */
    private String buildDraftBlock(List<DraftItem> draft,
                                   Map<String, MenuState.ProductRef> productByCode,
                                   Map<String, MenuState.SpecGroupRef> groupByCode) {
        if (draft == null || draft.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (DraftItem item : draft) {
            String code = findCode(item.productName(), productByCode);
            if (code == null) {
                // 草稿里的商品可能已下架：不为它编造编码，静默略过（下架商品本就不该出现在决策上下文里）
                continue;
            }
            List<String> options = new ArrayList<>();
            for (Map.Entry<String, MenuState.SpecGroupRef> entry : groupByCode.entrySet()) {
                for (String name : namesOf(item)) {
                    int index = indexOf(entry.getValue(), name);
                    if (index >= 0) {
                        options.add(entry.getKey() + ":" + index);
                    }
                }
            }
            lines.add(code + "{" + String.join(",", options) + "}x" + item.quantity());
        }
        if (lines.isEmpty()) {
            return "";
        }
        return "CURRENT DRAFT (already chosen, not yet paid):\n" + String.join("; ", lines);
    }

    private List<String> namesOf(DraftItem item) {
        return item.optionNames() == null ? List.of() : item.optionNames();
    }

    private int indexOf(MenuState.SpecGroupRef group, String optionName) {
        for (MenuState.OptionRef option : group.options()) {
            if (option.name().equals(optionName)) {
                return option.index();
            }
        }
        return -1;
    }

    private String findCode(String productName, Map<String, MenuState.ProductRef> productByCode) {
        if (productName == null) {
            return null;
        }
        for (MenuState.ProductRef ref : productByCode.values()) {
            if (productName.equals(ref.name())) {
                return ref.code();
            }
        }
        return null;
    }

    private String join(List<Integer> values) {
        List<String> texts = new ArrayList<>(values.size());
        for (Integer value : values) {
            texts.add(String.valueOf(value));
        }
        return String.join(",", texts);
    }

    /** 价差后缀：0.00 不显示；正数补 {@code +}（与 {@code MenuSearchTool} 的口径一致）。 */
    private String priceSuffix(String priceDelta) {
        if (priceDelta == null || priceDelta.startsWith("0.00") || priceDelta.startsWith("-0.00")) {
            return "";
        }
        return priceDelta.startsWith("-") ? " (" + priceDelta + ")" : " (+" + priceDelta + ")";
    }

    private List<SpecGroupVo> safeGroups(ProductVo product) {
        return product.getSpecGroups() == null ? List.of() : product.getSpecGroups();
    }

    /** 菜单段的中间产物：文本 + 商品映射表（两者必须同源，故一起返回）。 */
    private record MenuDraft(String text, Map<String, MenuState.ProductRef> productByCode) {
    }
}
