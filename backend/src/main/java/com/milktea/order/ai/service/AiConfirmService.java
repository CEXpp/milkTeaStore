package com.milktea.order.ai.service;

import com.milktea.order.ai.draft.DraftItem;
import com.milktea.order.ai.draft.DraftPricer;
import com.milktea.order.ai.dto.AiConfirmRequest;
import com.milktea.order.ai.session.SessionService;
import com.milktea.order.ai.vo.AiConfirmVo;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.order.dto.OrderCreateRequest;
import com.milktea.order.order.dto.OrderItemRequest;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.service.OrderService;
import com.milktea.order.order.vo.OrderCreateVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 草稿转订单服务（T32，落实 LLD 3.4 {@code /api/customer/ai/confirm-order} 与 LLD 6.6 末条）。
 *
 * <p>流程：草稿非空校验（1007）→ 草稿条目解析成计价输入 → 原子占取草稿（条件清空）→
 * 复用 {@link OrderService#createOrder(OrderCreateRequest, String)}
 * （同一套价格入口、校验与发号）且 {@code source=AI} → 返回 {@code orderId} 供前端连发 pay。</p>
 *
 * <p><b>与「对话」的边界</b>：本类不做任何模型调用，只把服务端草稿翻译成订单，因此
 * 「AI 不能替用户付钱」在这条链路上是结构性保证——本域不存在支付工具，订单停在
 * {@code PENDING_PAYMENT}，支付动作只能由用户在前端触发（SRS 约束三原则第三条）。</p>
 *
 * <p><b>原子性</b>：占取草稿、建单在同一事务内。占取用「仅当 {@code draft_items} 非空才清空」的
 * 条件更新实现，因此并发双击「立即支付」时只有一方能占取成功（另一方按 1007 拒绝），
 * 不会生成两笔订单；建单失败则连带回滚占取，草稿不会丢。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfirmService {

    private final SessionService sessionService;
    private final DraftPricer draftPricer;
    private final OrderService orderService;

    /**
     * 草稿单转正式订单（PENDING_PAYMENT）。
     *
     * @param request 转单请求（sessionId）
     * @return 订单标识与金额（LLD 3.4）
     * @throws BusinessException 角色非顾客时 403；会话归属他人时 403；草稿为空/会话过期时 1007；
     *                           草稿内商品已下架时 1002；门店暂停接单时 1006
     */
    @Transactional(rollbackFor = Exception.class)
    public AiConfirmVo confirmOrder(AiConfirmRequest request) {
        AuthContext.Principal principal = AuthContext.get();
        if (principal == null || !principal.isCustomer()) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "仅顾客可使用 AI 点单");
        }

        // 草稿非空校验（1007）：会话不存在、会话已过期作废都会走到这里，归属他人则提前 403
        List<DraftItem> items = sessionService.loadOwnedDraft(request.sessionId(), principal.getCustomerId());
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.DRAFT_EMPTY);
        }

        // 草稿只存名字与数量，此处翻译回「商品 id + 规格项 id」交给统一计价入口
        List<OrderItemRequest> orderItems = new ArrayList<>(items.size());
        for (DraftItem item : items) {
            DraftPricer.Resolved resolved = draftPricer.resolve(item.productName(), item.optionNames());
            if (resolved.failed()) {
                // 草稿写好后商品被下架：明确拒绝而不是静默改单，让顾客重新点单
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND.getCode(),
                        "草稿中「" + item.productName() + "」已下架，请重新点单");
            }
            orderItems.add(new OrderItemRequest(resolved.product().getId(), resolved.optionIds(), item.quantity()));
        }

        // 原子占取草稿：并发双击「立即支付」时只有一方能占取成功，另一方按草稿为空拒绝，
        // 从根上避免同一份草稿生成两笔订单；占取与建单同事务，建单失败会连带回滚
        if (!sessionService.claimDraft(request.sessionId())) {
            throw new BusinessException(ErrorCode.DRAFT_EMPTY);
        }

        OrderCreateRequest createRequest = new OrderCreateRequest();
        createRequest.setItems(orderItems);

        // 复用下单唯一入口：暂停校验(1006) + 实时计价与规格校验(1001/1002/1003) + 统一发号 + 快照落库
        OrderCreateVo order = orderService.createOrder(createRequest, Order.SOURCE_AI);

        log.info("[T32] AI 草稿转订单成功 orderId={} orderNo={} amount={} items={} sessionUuid={}",
                order.id(), order.orderNo(), order.totalAmount(), orderItems.size(), request.sessionId());
        return new AiConfirmVo(order.id(), order.orderNo(), order.totalAmount());
    }
}
