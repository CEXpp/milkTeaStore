package com.milktea.order.ai.decision;

import java.util.List;
import java.util.Map;

/**
 * 决策上下文（Jev 请求里的 {@code state}）与其「编码 ↔ 中文」映射表。
 *
 * <p>这是本方案「不做 RAG、改全量上下文注入」的落点：菜单很小（少量商品 + 四组规格），
 * 一次全量喂进去比向量检索更准——检索只会引入召回误差，而这里根本没有「捞不全」的问题。</p>
 *
 * <h2>三段式结构</h2>
 * <ul>
 *   <li>{@code menuBlock}：全量在售商品与规格（英文标签 + ASCII 下标），<b>由我们构造</b>；</li>
 *   <li>{@code preferenceBlock}：该顾客的历史偏好（同口径编码），可为空串；</li>
 *   <li>{@code requestBlock}：顾客原话，<b>唯一允许出现中文的业务数据</b>。</li>
 * </ul>
 *
 * <p>把「我们构造的内容」与「顾客原话」分成两段而不是混在一起，是为了让语言纪律<b>可被断言</b>：
 * 测试直接检查前两段没有任何中日韩字符；而模型无论如何都得读顾客原话——那是输入，不是我们的措辞。</p>
 *
 * <p><b>映射表与文本并存</b>：{@code menuBlock} 是给模型看的，映射表是给后端自己用的
 * （把模型返回的 {@code P03}、下标反向还原成中文商品名与规格名去落草稿）。
 * 两者必须来自同一次菜单读取，故一起持有——若各自查一次菜单，改价/上下架的并发窗口里就会错位。</p>
 *
 * @param menuBlock       菜单、规格与当前草稿区块
 * @param preferenceBlock 偏好区块（无历史为空串）
 * @param requestBlock    顾客请求区块
 * @param productByCode   商品编码 → 商品定义（保序，编码即 {@code P01} 形式）
 * @param groupByCode     规格组 code → 组定义（保序）
 * @param degraded        是否发生过标签字典降级（有过回落中文的选项或商品）
 */
public record MenuState(
        String menuBlock,
        String preferenceBlock,
        String requestBlock,
        Map<String, ProductRef> productByCode,
        Map<String, SpecGroupRef> groupByCode,
        boolean degraded) {

    /** 完整 state：三段按空行拼接，跳过空段。 */
    public String state() {
        StringBuilder text = new StringBuilder(menuBlock == null ? "" : menuBlock);
        if (preferenceBlock != null && !preferenceBlock.isBlank()) {
            text.append("\n\n").append(preferenceBlock);
        }
        if (requestBlock != null && !requestBlock.isBlank()) {
            text.append("\n\n").append(requestBlock);
        }
        return text.toString();
    }

    /** 取商品定义；不存在返回 {@code null}。 */
    public ProductRef product(String productCode) {
        return productCode == null || productByCode == null ? null : productByCode.get(productCode);
    }

    /** 商品编码是否为当前在售商品。 */
    public boolean hasProduct(String productCode) {
        return product(productCode) != null;
    }

    /**
     * 中文商品名 → 商品编码。
     *
     * @param productName 中文商品名（来自草稿单或偏好）
     * @return 编码；不在当前在售集合内返回 {@code null}（草稿可能留存了下架商品）
     */
    public String productCodeOf(String productName) {
        if (productName == null || productByCode == null) {
            return null;
        }
        for (ProductRef ref : productByCode.values()) {
            if (productName.equals(ref.name())) {
                return ref.code();
            }
        }
        return null;
    }

    /**
     * 取某商品适用的规格组 code 列表（按菜单中的组顺序）。
     *
     * @param productCode 商品编码
     * @return 组 code 列表；商品不存在返回空列表
     */
    public List<String> groupCodesOf(String productCode) {
        ProductRef ref = product(productCode);
        return ref == null || ref.groupCodes() == null ? List.of() : ref.groupCodes();
    }

    /**
     * 中文选项名 → 组内下标。
     *
     * @param groupCode  组 code
     * @param optionName 中文选项名
     * @return 下标；未命中返回 -1
     */
    public int optionIndexOf(String groupCode, String optionName) {
        SpecGroupRef group = groupByCode == null ? null : groupByCode.get(groupCode);
        if (group == null || optionName == null) {
            return -1;
        }
        for (OptionRef option : group.options()) {
            if (optionName.equals(option.name())) {
                return option.index();
            }
        }
        return -1;
    }

    /**
     * 组内下标 → 中文选项名。
     *
     * @param groupCode 组 code
     * @param index     下标
     * @return 中文名；下标不存在返回 {@code null}
     */
    public String optionNameOf(String groupCode, int index) {
        SpecGroupRef group = groupByCode == null ? null : groupByCode.get(groupCode);
        if (group == null) {
            return null;
        }
        for (OptionRef option : group.options()) {
            if (option.index() == index) {
                return option.name();
            }
        }
        return null;
    }

    /**
     * 商品定义。
     *
     * @param code       编码（{@code P01}…，决策答案的唯一标识）
     * @param name       中文名（回写草稿用——{@code DraftOrderTool} 按中文名解析）
     * @param label      英文标签（进 state 与 choice 题面；字典未命中时回落中文名）
     * @param basePrice  基础价（两位小数字符串，让模型在预算约束下选品）
     * @param groupCodes 该商品适用的规格组 code（保序）
     */
    public record ProductRef(String code, String name, String label, String basePrice, List<String> groupCodes) {
    }

    /**
     * 规格组定义。
     *
     * @param code        组 code（{@code CUP_SIZE} 等，本身即英文，直接作为 state 与题面用）
     * @param name        中文组名（仅用于落草稿与展示，不进 state）
     * @param multiSelect 是否多选（加料）
     * @param options     启用中的选项（按 sort_order 升序，{@link OptionRef#index()} 即其下标）
     */
    public record SpecGroupRef(String code, String name, boolean multiSelect, List<OptionRef> options) {

        /** 按下标取选项；越界返回 {@code null}。 */
        public OptionRef at(int index) {
            for (OptionRef option : options) {
                if (option.index() == index) {
                    return option;
                }
            }
            return null;
        }
    }

    /**
     * 规格选项。
     *
     * @param index      组内下标（0 基；Jev 的答案用它，故必须与菜单顺序严格一致）
     * @param id         规格项主键（计价引擎入参）
     * @param name       中文选项名（回写草稿用——{@code DraftOrderTool} 按中文名解析）
     * @param label      英文标签（进 state；字典未命中时回落为中文名）
     * @param priceDelta 价差（两位小数字符串，让模型感知加料成本）
     */
    public record OptionRef(int index, Long id, String name, String label, String priceDelta) {
    }
}
