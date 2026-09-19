package com.milktea.order.ai.service;

import com.milktea.order.ai.OrderAssistant;
import com.milktea.order.ai.dto.AiChatRequest;
import com.milktea.order.ai.session.AiSessionEntity;
import com.milktea.order.ai.session.SessionService;
import com.milktea.order.ai.vo.AiChatVo;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 对话服务（T29 最小实现；完整流程见 LLD 6.6，归属 T31）。
 *
 * <p>本任务只做三步：鉴权取顾客 → 定位/新建会话 → 调模型取回复并续期会话。
 * LLD 6.6 的「读草稿组装 TEXT/CARD」依赖 T30 的工具集，限流与降级话术归属 T31。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final OrderAssistant orderAssistant;
    private final SessionService sessionService;

    /**
     * 一轮对话。
     *
     * @param request 对话请求（sessionId 可空 + message）
     * @return TEXT 形态响应；本任务无草稿卡片
     * @throws BusinessException 角色非顾客时 403；模型不可用时 1008
     */
    public AiChatVo chat(AiChatRequest request) {
        AuthContext.Principal principal = AuthContext.get();
        if (principal == null || !principal.isCustomer()) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "仅顾客可使用 AI 点单");
        }

        AiSessionEntity session = sessionService.locateOrCreate(request.getSessionId(), principal.getCustomerId());
        try {
            String reply = orderAssistant.chat(session.getSessionUuid(), request.getMessage());
            sessionService.refreshExpiry(session);
            return AiChatVo.text(session.getSessionUuid(), reply);
        } catch (Exception e) {
            // SRS 5.5 / LLD 6.6 步骤 2：模型超时或 HTTP 错误 → 1008 降级，不影响手动点单主链路。
            // data.fallbackText 属 T31 交付内容（排期 T31 卡「异常分支：code=1008+fallbackText」）。
            log.warn("[T29] AI 模型调用失败，降级返回 1008：sessionUuid={}", session.getSessionUuid(), e);
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
        }
    }
}
