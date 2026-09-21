package com.milktea.order.ai.service;

import com.milktea.order.ai.OrderAssistant;
import com.milktea.order.ai.draft.DraftItem;
import com.milktea.order.ai.draft.DraftPricer;
import com.milktea.order.ai.dto.AiChatRequest;
import com.milktea.order.ai.ratelimit.CustomerRateLimiter;
import com.milktea.order.ai.session.AiSessionEntity;
import com.milktea.order.ai.session.SessionService;
import com.milktea.order.ai.vo.AiChatVo;
import com.milktea.order.ai.vo.AiDraftVo;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.common.util.MoneyUtils;
import com.milktea.order.order.dto.OptionSnapshot;
import com.milktea.order.order.dto.PricedItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 对话服务（T31，落实 LLD 6.6 chat 接口实现流程）。
 *
 * <p>四步流程：</p>
 * <ol>
 *   <li>鉴权取 {@code customerId} → 按顾客限流 → 定位/新建会话；</li>
 *   <li>{@code assistant.chat(...)} 调 LangChain4j（模型自动多轮工具调用），
 *       超时/HTTP 错误 → 捕获并抛 1008 降级；</li>
 *   <li>读 {@code ai_session.draft_items}：非空则组装 CARD（草稿 + 计价引擎结果），否则 TEXT；</li>
 *   <li>刷新 {@code expires_at}（本轮的 USER/ASSISTANT 消息由
 *       {@link com.milktea.order.ai.session.ChatMemoryStoreImpl} 落库）。</li>
 * </ol>
 *
 * <p><b>草稿金额不由模型提供</b>：卡片里的单价/小计/合计一律经 {@link DraftPricer} 走
 * {@code PricingService} 现算（LLD 4.5 唯一算价入口）；模型侧不存在任何价格字段。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final OrderAssistant orderAssistant;
    private final SessionService sessionService;
    private final CustomerRateLimiter rateLimiter;
    private final DraftPricer draftPricer;

    /**
     * 一轮对话，流程见类注释（LLD 6.6 四步）。
     *
     * @param request 对话请求（sessionId 可空 + message）
     * @return 响应 data（TEXT 或 CARD，见 LLD 3.4）
     * @throws BusinessException 角色非顾客时 403；超出限流时 1009；模型不可用时 1008
     */
    public AiChatVo chat(AiChatRequest request) {
        // 步骤 1：鉴权 + 限流 + 定位会话
        AuthContext.Principal principal = AuthContext.get();
        if (principal == null || !principal.isCustomer()) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "仅顾客可使用 AI 点单");
        }
        Long customerId = principal.getCustomerId();
        if (!rateLimiter.tryAcquire(customerId)) {
            // LLD 9.2：按顾客限流 10 次/分钟，保护免费模型额度
            log.warn("[T31] AI 对话触发限流：customerId={} limit={}/分钟", customerId, rateLimiter.limitPerMinute());
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
        AiSessionEntity session = sessionService.locateOrCreate(request.getSessionId(), customerId);

        // 步骤 2：模型调用（模型自动多轮工具调用）
        String reply;
        try {
            reply = orderAssistant.chat(session.getSessionUuid(), request.getMessage());
        } catch (Exception e) {
            // LLD 3.4 / LLD 6.6：模型超时或 HTTP 错误 → 1008 降级，不影响手动点单主链路
            log.warn("[T31] AI 模型调用失败，降级返回 1008：sessionUuid={}", session.getSessionUuid(), e);
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
        }

        // 步骤 3：读草稿组装 TEXT / CARD
        AiChatVo response = composeReply(session.getSessionUuid(), reply);

        // 步骤 4：刷新过期时间
        sessionService.refreshExpiry(session);
        return response;
    }

    /**
     * 组装响应：草稿非空 → CARD（LLD 3.4 契约），否则 TEXT。
     *
     * <p>草稿条目在写好后仍可能因商品下架/规格停用而失去价格，此类条目不计入卡片
     * （下单侧同样会被计价引擎拒绝），避免给出失真的金额；若因此没有任何可计价条目，
     * 退化为 TEXT 而非返回空卡片。</p>
     */
    private AiChatVo composeReply(String sessionUuid, String reply) {
        List<DraftItem> items = sessionService.loadDraft(sessionUuid);
        if (items.isEmpty()) {
            return AiChatVo.text(sessionUuid, reply);
        }
        List<AiDraftVo.Item> cardItems = new ArrayList<>(items.size());
        BigDecimal total = BigDecimal.ZERO;
        for (DraftItem item : items) {
            PricedItem priced = draftPricer.priceOrNull(item);
            if (priced == null) {
                log.warn("[T31] 草稿条目已无法计价，未计入卡片：sessionUuid={} productName={}",
                        sessionUuid, item.productName());
                continue;
            }
            List<String> optionNames = priced.getOptions() == null ? List.of()
                    : priced.getOptions().stream().map(OptionSnapshot::getOptionName).toList();
            cardItems.add(new AiDraftVo.Item(priced.getProductName(), optionNames, priced.getQuantity(),
                    MoneyUtils.format(priced.getUnitPrice()), MoneyUtils.format(priced.getItemAmount())));
            total = total.add(priced.getItemAmount());
        }
        if (cardItems.isEmpty()) {
            return AiChatVo.text(sessionUuid, reply);
        }
        return AiChatVo.card(sessionUuid, reply, new AiDraftVo(cardItems, MoneyUtils.format(total)));
    }
}
