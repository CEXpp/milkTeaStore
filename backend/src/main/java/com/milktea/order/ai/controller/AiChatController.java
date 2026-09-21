package com.milktea.order.ai.controller;

import com.milktea.order.ai.dto.AiChatRequest;
import com.milktea.order.ai.dto.AiConfirmRequest;
import com.milktea.order.ai.service.AiChatService;
import com.milktea.order.ai.service.AiConfirmService;
import com.milktea.order.ai.vo.AiConfirmVo;
import com.milktea.order.ai.vo.AiFallbackVo;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 顾客端 AI 点单接口（LLD 3.4）。
 *
 * <p>{@code /chat} 为对话端点（T31：提示词全文 + 限流 + 降级 + TEXT/CARD），
 * {@code /confirm-order} 为草稿转订单端点（T32）。两者同属「AI 点单」链路但都不包含支付动作
 * ——AI 域不提供支付工具，订单只到 {@code PENDING_PAYMENT}，付钱由用户在前端触发。</p>
 *
 * <p>鉴权：路径 {@code /api/customer/ai/**} 已在 {@code JwtAuthenticationFilter} 的顾客 JWT 矩阵内，
 * 会话与草稿绑定顾客身份（SRS 5.4）。</p>
 */
@RestController
@RequestMapping("/api/customer/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;
    private final AiConfirmService aiConfirmService;

    /**
     * POST /api/customer/ai/chat —— 对话（LLD 3.4）。
     *
     * <p>首轮可不带 sessionId（后端新建并返回）。响应 data 有三种形态：{@code replyType=CARD}
     * （含草稿单）、{@code replyType=TEXT}（规格追问等）、以及模型不可用时的
     * {@code code=1008 + data.fallbackText} 降级形态——后者按 LLD 3.4 要求在 data 里
     * 带上给顾客看的兜底话术，故在此就地组装响应，而不走全局异常处理器（那里不带 data）。</p>
     */
    @PostMapping("/chat")
    public R<?> chat(@Valid @RequestBody AiChatRequest request) {
        try {
            return R.ok(aiChatService.chat(request));
        } catch (BusinessException ex) {
            if (ex.getCode() != ErrorCode.AI_UNAVAILABLE.getCode()) {
                throw ex;
            }
            return R.fail(ex.getCode(), ex.getMessage(), AiFallbackVo.aiUnavailable());
        }
    }

    /**
     * POST /api/customer/ai/confirm-order —— 草稿单转正式订单（LLD 3.4）。
     *
     * <p>快捷直付第一步：把服务端草稿转成 {@code PENDING_PAYMENT} 订单并清空草稿，
     * 前端拿到 {@code orderId} 后立即连发 pay 完成支付闭环（UI 上是「立即支付」一个按钮）。
     * 本端点不触发支付——AI 域不存在支付工具，付钱只能由用户在前端点。</p>
     */
    @PostMapping("/confirm-order")
    public R<AiConfirmVo> confirmOrder(@Valid @RequestBody AiConfirmRequest request) {
        return R.ok(aiConfirmService.confirmOrder(request));
    }
}
