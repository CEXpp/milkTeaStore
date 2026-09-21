package com.milktea.order.ai.controller;

import com.milktea.order.ai.dto.AiChatRequest;
import com.milktea.order.ai.service.AiChatService;
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
 * <p>T31 在此补齐提示词全文、限流与降级话术；T32 在同一控制器补 {@code /confirm-order}。
 * 鉴权：路径 {@code /api/customer/ai/**} 已在 {@code JwtAuthenticationFilter} 的顾客 JWT 矩阵内，
 * 会话与草稿绑定顾客身份（SRS 5.4）。</p>
 */
@RestController
@RequestMapping("/api/customer/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;

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
}
