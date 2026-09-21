package com.milktea.order.ai.tools;

import com.milktea.order.ai.session.SessionService;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.util.MoneyUtils;
import com.milktea.order.order.dto.OptionSnapshot;
import com.milktea.order.order.dto.OrderItemRequest;
import com.milktea.order.order.dto.PricedItem;
import com.milktea.order.product.service.MenuService;
import com.milktea.order.product.service.PricingService;
import com.milktea.order.product.vo.ProductVo;
import com.milktea.order.product.vo.SpecGroupVo;
import com.milktea.order.product.vo.SpecOptionVo;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 工具 · 改/看/清草稿单（T30，LLD 6.3 第二至第四个工具）。
 *
 * <p><b>工具只描述数据、不生成数据</b>——本类是 SRS「AI 约束三原则」的后端硬约束点：</p>
 * <ol>
 *   <li>商品名精确匹配 → LIKE 兜底；都命中不到返回「未找到该商品」，模型据此道歉并推荐，
 *       编造的商品无法进入草稿（AC-12 后端侧）。商品只从在售集合里找，下架商品同样拒绝。</li>
 *   <li>选项名按商品适用的启用规格组逐个匹配；未知选项名直接拒绝，单选组漏选返回
 *       「该商品需要选择{组名}」提示模型追问顾客。</li>
 *   <li>金额不来自模型输入：一律调 {@link PricingService#calculatePrice}（LLD 4.5 全系统唯一算价入口），
 *       模型侧不存在任何价格字段。</li>
 * </ol>
 *
 * <p><b>草稿单口径</b>（{@code ai_session.draft_items}，LLD 6.5）：JSON 数组
 * {@code [{productName, optionNames[], quantity}]}——只存名字与数量，不存价格；
 * 单价与合计在每次读写时由计价引擎现算，避免价格快照与商品改价脱节。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DraftOrderTool {

    /** LLD 6.3 规定的「未找到商品」反馈文本 */
    private static final String NOT_FOUND_PRODUCT = "未找到该商品";

    /** 草稿为空时的反馈文本 */
    private static final String DRAFT_EMPTY = "当前草稿单为空。";

    /** 会话缺失/过期时的反馈文本 */
    private static final String SESSION_MISSING = "会话不存在或已过期，请重新开始点单。";

    private final MenuService menuService;
    private final PricingService pricingService;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    /**
     * 增改草稿单（LLD 6.3 签名逐字）：同名同规格的条目按「覆盖数量」处理，其余追加。
     *
     * @param productName 商品名
     * @param optionNames 选项名列表，如 [大杯,少冰,五分糖,珍珠]
     * @param quantity    数量，默认 1
     * @param sessionId   会话标识，由 LangChain4j 的 {@code @MemoryId} 注入
     * @return 供模型阅读的结果文本（含单价、小计与草稿合计）
     */
    @Tool("增改草稿单：按商品名与选项名写入/覆盖/追加购物草稿。")
    public String updateDraftOrder(
            @P("商品名") String productName,
            @P("选项名列表，如 [大杯,少冰,五分糖,珍珠]") List<String> optionNames,
            @P(value = "数量，默认 1", defaultValue = "1") int quantity,
            @ToolMemoryId String sessionId) {
        if (!StringUtils.hasText(productName)) {
            return "请告诉我商品名。";
        }
        if (quantity < OrderItemRequest.MIN_QUANTITY || quantity > OrderItemRequest.MAX_QUANTITY) {
            return "数量需在 " + OrderItemRequest.MIN_QUANTITY + ".." + OrderItemRequest.MAX_QUANTITY + " 之间。";
        }
        int qty = quantity;

        Resolved resolved = resolve(productName, optionNames);
        if (resolved.failed()) {
            return resolved.error();
        }

        PricedItem priced;
        try {
            priced = pricingService.calculatePrice(
                    new OrderItemRequest(resolved.product().getId(), resolved.optionIds(), qty));
        } catch (BusinessException e) {
            // 计价引擎同时承担 LLD 3.2 的下单校验，其文案即最准确的反馈
            return e.getMessage();
        }

        DraftItem incoming = new DraftItem(priced.getProductName(),
                priced.getOptions() == null ? List.of()
                        : priced.getOptions().stream().map(OptionSnapshot::getOptionName).toList(),
                qty);
        List<DraftItem> items = readDraft(sessionId);
        int same = indexOfSameItem(items, incoming);
        if (same >= 0) {
            items.set(same, incoming);
        } else {
            items.add(incoming);
        }
        if (!sessionService.writeDraft(sessionId, writeDraftJson(items))) {
            return SESSION_MISSING;
        }

        BigDecimal total = totalAmount(items);
        return "已加入草稿：" + describe(incoming)
                + "，单价 " + MoneyUtils.format(priced.getUnitPrice())
                + " 元，小计 " + MoneyUtils.format(priced.getItemAmount())
                + " 元；草稿共 " + items.size() + " 项，合计 " + MoneyUtils.format(total) + " 元。";
    }

    /**
     * 查看当前草稿单（LLD 6.3 签名逐字）。
     *
     * @param sessionId 会话标识，由 {@code @ToolMemoryId} 注入
     * @return 草稿条目与合计；空草稿返回空态文本
     */
    @Tool("查看当前草稿单内容。")
    public String getDraftOrder(@ToolMemoryId String sessionId) {
        List<DraftItem> items = readDraft(sessionId);
        if (items.isEmpty()) {
            return DRAFT_EMPTY;
        }
        StringBuilder text = new StringBuilder("当前草稿单共 " + items.size() + " 项：");
        BigDecimal total = BigDecimal.ZERO;
        boolean priced = true;
        for (int i = 0; i < items.size(); i++) {
            DraftItem item = items.get(i);
            text.append(i + 1).append(". ").append(describe(item));
            BigDecimal amount = itemAmount(item);
            if (amount == null) {
                // 商品下架或规格停用：如实告知，不编造金额
                priced = false;
                text.append("（该商品或规格已下架，金额待重新确认）");
            } else {
                text.append(" 小计 ").append(MoneyUtils.format(amount)).append(" 元");
                total = total.add(amount);
            }
            text.append("；");
        }
        if (priced) {
            text.append("合计 ").append(MoneyUtils.format(total)).append(" 元。");
        }
        return text.toString();
    }

    /**
     * 清空草稿单（LLD 6.3 签名逐字）。
     *
     * @param sessionId 会话标识，由 {@code @ToolMemoryId} 注入
     * @return 结果文本
     */
    @Tool("清空草稿单。用户说不要了/重新点时使用。")
    public String clearDraftOrder(@ToolMemoryId String sessionId) {
        return sessionService.clearDraft(sessionId) ? "已清空草稿单。" : SESSION_MISSING;
    }

    /**
     * 商品名 + 选项名 → 商品与规格项 id（计价引擎入参）。
     *
     * <p>商品名先精确匹配、再取 LIKE 命中的第一条（菜单已按 sort_order 升序，取首条即最上架位）；
     * 选项名逐个匹配商品适用的启用规格组，未命中的名字与单选组漏选分别给出反馈文本。</p>
     *
     * @return 解析结果；失败时 {@code error} 非空、{@code product} 为 null
     */
    private Resolved resolve(String productName, List<String> optionNames) {
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
     * 草稿条目现算小计：单价与金额一律走计价引擎。
     *
     * @return 小计；商品下架或规格停用导致无法计价时返回 {@code null}
     */
    private BigDecimal itemAmount(DraftItem item) {
        Resolved resolved = resolve(item.productName(), item.optionNames());
        if (resolved.failed()) {
            return null;
        }
        try {
            return pricingService.calculatePrice(new OrderItemRequest(resolved.product().getId(),
                    resolved.optionIds(), item.quantity())).getItemAmount();
        } catch (BusinessException e) {
            log.warn("[T30] 草稿条目计价失败：productName={} reason={}", item.productName(), e.getMessage());
            return null;
        }
    }

    /** 草稿合计 = 各条小计之和（计价失败条目按 0 计，已在条目文本中标注）。 */
    private BigDecimal totalAmount(List<DraftItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (DraftItem item : items) {
            BigDecimal amount = itemAmount(item);
            if (amount != null) {
                total = total.add(amount);
            }
        }
        return total;
    }

    /** 草稿条目文本：{@code 珍珠奶茶(大杯/少冰) ×2}。 */
    private String describe(DraftItem item) {
        StringBuilder text = new StringBuilder(item.productName());
        if (item.optionNames() != null && !item.optionNames().isEmpty()) {
            text.append('(').append(String.join("/", item.optionNames())).append(')');
        }
        return text.append(" ×").append(item.quantity()).toString();
    }

    /** 「同名 + 同规格」视为同一条目：命中则覆盖数量而非追加（LLD 6.3「写入/覆盖/追加」）。 */
    private int indexOfSameItem(List<DraftItem> items, DraftItem target) {
        for (int i = 0; i < items.size(); i++) {
            DraftItem item = items.get(i);
            if (target.productName().equals(item.productName())
                    && target.optionNames().equals(item.optionNames() == null ? List.of() : item.optionNames())) {
                return i;
            }
        }
        return -1;
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

    /** 读草稿单；JSON 损坏时按空草稿处理并告警，避免一条脏数据让整轮对话不可用。 */
    private List<DraftItem> readDraft(String sessionId) {
        String json = sessionService.readDraft(sessionId);
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            DraftItem[] parsed = objectMapper.readValue(json, DraftItem[].class);
            return parsed == null ? new ArrayList<>() : new ArrayList<>(List.of(parsed));
        } catch (JacksonException e) {
            log.warn("[T30] 草稿单 JSON 解析失败，按空草稿处理：sessionUuid={}", sessionId, e);
            return new ArrayList<>();
        }
    }

    private String writeDraftJson(List<DraftItem> items) {
        return objectMapper.writeValueAsString(items);
    }

    /**
     * 草稿单条目（{@code ai_session.draft_items} 的 JSON 元素，LLD 6.5 口径）。
     *
     * <p>只含名字与数量、不含价格——金额每次由计价引擎现算。</p>
     */
    public record DraftItem(String productName, List<String> optionNames, Integer quantity) {
    }

    /**
     * 解析结果：成功时 {@code product}/{@code optionIds} 可用；失败时 {@code error} 为反馈文本。
     */
    private record Resolved(ProductVo product, List<Long> optionIds, String error) {

        static Resolved success(ProductVo product, List<Long> optionIds) {
            return new Resolved(product, optionIds, null);
        }

        static Resolved failure(String error) {
            return new Resolved(null, List.of(), error);
        }

        boolean failed() {
            return error != null;
        }
    }
}
