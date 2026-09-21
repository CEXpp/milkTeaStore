package com.milktea.order.ai.tools;

import com.milktea.order.ai.draft.DraftItem;
import com.milktea.order.ai.draft.DraftPricer;
import com.milktea.order.ai.session.SessionService;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.util.MoneyUtils;
import com.milktea.order.order.dto.OptionSnapshot;
import com.milktea.order.order.dto.OrderItemRequest;
import com.milktea.order.order.dto.PricedItem;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI 工具 · 改/看/清草稿单（T30，LLD 6.3 第二至第四个工具）。
 *
 * <p><b>工具只描述数据、不生成数据</b>——本类是 SRS「AI 约束三原则」的后端硬约束点：</p>
 * <ol>
 *   <li>商品名精确匹配 → LIKE 兜底；都命中不到返回「未找到该商品」，模型据此道歉并推荐，
 *       编造的商品无法进入草稿（AC-12 后端侧）。商品只从在售集合里找，下架商品同样拒绝。</li>
 *   <li>选项名按商品适用的启用规格组逐个匹配；未知选项名直接拒绝，单选组漏选返回
 *       「该商品需要选择{组名}」提示模型追问顾客。</li>
 *   <li>金额不来自模型输入：一律调 {@link com.milktea.order.product.service.PricingService}
 *       （LLD 4.5 全系统唯一算价入口），模型侧不存在任何价格字段。</li>
 * </ol>
 *
 * <p>「名字 → 实体 → 金额」的解析与 T31 组装 CARD 时共用 {@link DraftPricer}，保证两处口径一致。</p>
 *
 * <p><b>草稿单口径</b>（{@code ai_session.draft_items}，LLD 6.5）：JSON 数组
 * {@code [{productName, optionNames[], quantity}]}——只存名字与数量，不存价格；
 * 单价与合计在每次读写时由计价引擎现算，避免价格快照与商品改价脱节。</p>
 */
@Component
@RequiredArgsConstructor
public class DraftOrderTool {

    /** 草稿为空时的反馈文本 */
    private static final String DRAFT_EMPTY = "当前草稿单为空。";

    /** 会话缺失/过期时的反馈文本 */
    private static final String SESSION_MISSING = "会话不存在或已过期，请重新开始点单。";

    private final DraftPricer draftPricer;
    private final SessionService sessionService;

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

        DraftPricer.Resolved resolved = draftPricer.resolve(productName, optionNames);
        if (resolved.failed()) {
            return resolved.error();
        }

        PricedItem priced;
        try {
            priced = draftPricer.price(resolved, quantity);
        } catch (BusinessException e) {
            // 计价引擎同时承担 LLD 3.2 的下单校验，其文案即最准确的反馈
            return e.getMessage();
        }

        DraftItem incoming = new DraftItem(priced.getProductName(),
                priced.getOptions() == null ? List.of()
                        : priced.getOptions().stream().map(OptionSnapshot::getOptionName).toList(),
                quantity);
        List<DraftItem> items = sessionService.loadDraft(sessionId);
        int same = indexOfSameItem(items, incoming);
        if (same >= 0) {
            items.set(same, incoming);
        } else {
            items.add(incoming);
        }
        if (!sessionService.saveDraft(sessionId, items)) {
            return SESSION_MISSING;
        }

        BigDecimal total = draftPricer.totalAmount(items);
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
        List<DraftItem> items = sessionService.loadDraft(sessionId);
        if (items.isEmpty()) {
            return DRAFT_EMPTY;
        }
        StringBuilder text = new StringBuilder("当前草稿单共 " + items.size() + " 项：");
        BigDecimal total = BigDecimal.ZERO;
        boolean priced = true;
        for (int i = 0; i < items.size(); i++) {
            DraftItem item = items.get(i);
            text.append(i + 1).append(". ").append(describe(item));
            PricedItem itemPriced = draftPricer.priceOrNull(item);
            if (itemPriced == null) {
                // 商品下架或规格停用：如实告知，不编造金额
                priced = false;
                text.append("（该商品或规格已下架，金额待重新确认）");
            } else {
                text.append(" 小计 ").append(MoneyUtils.format(itemPriced.getItemAmount())).append(" 元");
                total = total.add(itemPriced.getItemAmount());
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
}
