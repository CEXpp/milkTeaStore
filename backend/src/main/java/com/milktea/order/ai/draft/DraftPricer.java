package com.milktea.order.ai.draft;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.product.service.MenuService;
import com.milktea.order.product.service.PricingService;
import com.milktea.order.order.dto.OrderItemRequest;
import com.milktea.order.order.dto.PricedItem;
import com.milktea.order.product.vo.ProductVo;
import com.milktea.order.product.vo.SpecGroupVo;
import com.milktea.order.product.vo.SpecOptionVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 草稿单的「名字 → 实体 → 金额」解析器（T30 抽出，T31 复用）。
 *
 * <p>AI 侧的两个使用方共享同一份口径，避免两处实现漂移：</p>
 * <ul>
 *   <li>{@code DraftOrderTool}（T30）：改写草稿单时校验商品/规格并算出反馈金额；</li>
 *   <li>{@code AiChatService}（T31）：组装 CARD 响应时把草稿单条目算成单价/小计/合计。</li>
 * </ul>
 *
 * <p><b>校验即「AI 约束三原则」的落地层</b>：商品只从在售集合里找（下架商品天然不可见），
 * 选项名逐个匹配商品适用的启用规格组，未知名字与单选组漏选一律拒绝并给出反馈文本；
 * 金额只来自 {@link PricingService#calculatePrice}，模型输入中不存在任何价格字段。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DraftPricer {

    /** LLD 6.3 规定的「未找到商品」反馈文本 */
    public static final String NOT_FOUND_PRODUCT = "未找到该商品";

    private final MenuService menuService;
    private final PricingService pricingService;

    /**
     * 商品名 + 选项名 → 商品与规格项 id（计价引擎入参）。
     *
     * <p>商品名先精确匹配、再取 LIKE 命中的第一条（菜单已按 sort_order 升序，取首条即最上架位）；
     * 选项名逐个匹配商品适用的启用规格组，未命中的名字与单选组漏选分别给出反馈文本。</p>
     *
     * @param productName 商品名
     * @param optionNames 选项名列表，可为 null
     * @return 解析结果；失败时 {@code error} 非空、{@code product} 为 null
     */
    public Resolved resolve(String productName, List<String> optionNames) {
        if (!StringUtils.hasText(productName)) {
            return Resolved.failure("请告诉我商品名。");
        }
        String trimmed = productName.trim();
        List<ProductVo> candidates = menuService.searchOnSaleProducts(trimmed);
        if (candidates.isEmpty()) {
            return Resolved.failure(NOT_FOUND_PRODUCT);
        }
        ProductVo product = candidates.stream()
                .filter(candidate -> trimmed.equals(candidate.getName()))
                .findFirst()
                .orElse(candidates.getFirst());

        List<SpecGroupVo> groups = product.getSpecGroups() == null ? List.of() : product.getSpecGroups();
        List<String> names = normalizeOptionNames(optionNames);

        // 规格组 → 命中的规格项（保持传入顺序去重）
        Map<SpecGroupVo, List<SpecOptionVo>> matched = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (String name : names) {
            boolean hit = false;
            for (SpecGroupVo group : groups) {
                for (SpecOptionVo option : group.getOptions() == null ? List.<SpecOptionVo>of() : group.getOptions()) {
                    if (name.equals(option.getName())) {
                        matched.computeIfAbsent(group, key -> new ArrayList<>()).add(option);
                        hit = true;
                    }
                }
            }
            if (!hit) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            return Resolved.failure("未找到规格项：" + String.join("、", unknown) + "。");
        }
        for (SpecGroupVo group : groups) {
            List<SpecOptionVo> picked = matched.getOrDefault(group, List.of());
            if (Boolean.TRUE.equals(group.getMultiSelect())) {
                continue;
            }
            if (picked.isEmpty()) {
                // LLD 6.3「单选组选项缺失时返回该商品需要选择杯型」——组名来自规格表，不硬编码
                return Resolved.failure("该商品需要选择" + group.getName() + "。");
            }
            if (picked.size() > 1) {
                return Resolved.failure("「" + group.getName() + "」只能选择 1 项。");
            }
        }

        // 按规格组 sort_order、组内规格项 sort_order 展开，与计价引擎的快照口径一致
        List<Long> optionIds = new ArrayList<>();
        for (SpecGroupVo group : groups) {
            for (SpecOptionVo option : matched.getOrDefault(group, List.of())) {
                optionIds.add(option.getId());
            }
        }
        return Resolved.success(product, optionIds);
    }

    /**
     * 计价：单价与金额一律走 {@link PricingService#calculatePrice}（LLD 4.5 唯一算价入口）。
     *
     * @param resolved 已解析的商品与规格项
     * @param quantity 数量
     * @return 计价结果（含商品名快照与规格快照）
     * @throws BusinessException 计价引擎的下单校验未通过（1001/1002/1003）
     */
    public PricedItem price(Resolved resolved, int quantity) {
        return pricingService.calculatePrice(
                new OrderItemRequest(resolved.product().getId(), resolved.optionIds(), quantity));
    }

    /**
     * 草稿条目现算价格。
     *
     * <p>商品下架、规格停用或数量非法等情况下返回 {@code null}（并告警），由调用方决定降级展示方式
     * ——草稿单可存活到商品改价/下架之后，故此处必须容错而不是抛出。</p>
     *
     * @param item 草稿条目
     * @return 计价结果；无法计价时返回 {@code null}
     */
    public PricedItem priceOrNull(DraftItem item) {
        if (item == null || item.quantity() == null) {
            return null;
        }
        Resolved resolved = resolve(item.productName(), item.optionNames());
        if (resolved.failed()) {
            log.warn("[AI] 草稿条目解析失败：productName={} reason={}", item.productName(), resolved.error());
            return null;
        }
        try {
            return price(resolved, item.quantity());
        } catch (BusinessException e) {
            log.warn("[AI] 草稿条目计价失败：productName={} reason={}", item.productName(), e.getMessage());
            return null;
        }
    }

    /**
     * 草稿合计 = 各条小计之和（无法计价的条目按 0 计，调用方已在条目文本中标注）。
     *
     * @param items 草稿条目
     * @return 合计金额
     */
    public BigDecimal totalAmount(List<DraftItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (DraftItem item : items) {
            PricedItem priced = priceOrNull(item);
            if (priced != null) {
                total = total.add(priced.getItemAmount());
            }
        }
        return total;
    }

    /** 选项名清洗：去空白、去空串、保序去重。 */
    private List<String> normalizeOptionNames(List<String> optionNames) {
        if (optionNames == null || optionNames.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(optionNames.size());
        for (String name : optionNames) {
            if (StringUtils.hasText(name)) {
                String trimmed = name.trim();
                if (!names.contains(trimmed)) {
                    names.add(trimmed);
                }
            }
        }
        return names;
    }

    /**
     * 解析结果：成功时 {@code product}/{@code optionIds} 可用；失败时 {@code error} 为反馈文本。
     */
    public record Resolved(ProductVo product, List<Long> optionIds, String error) {

        static Resolved success(ProductVo product, List<Long> optionIds) {
            return new Resolved(product, optionIds, null);
        }

        static Resolved failure(String error) {
            return new Resolved(null, List.of(), error);
        }

        public boolean failed() {
            return error != null;
        }
    }
}
