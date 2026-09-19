package com.milktea.order.ai.controller;

import com.milktea.order.ai.dto.AiChatRequest;
import com.milktea.order.ai.service.AiChatService;
import com.milktea.order.ai.vo.AiChatVo;
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
 * <p>本任务（T29）只落最小 chat 端点，用于验证模型连通与记忆持久化；
 * T31 在此补齐提示词全文、限流与降级话术，T32 在同一控制器补 {@code /confirm-order}。</p>
 *
 * <p>鉴权：路径 {@code /api/customer/ai/**} 已在 {@code JwtAuthenticationFilter} 的顾客 JWT 矩阵内，
 * 会话与草稿绑定顾客身份（SRS 5.4）。</p>
 */
@RestController
@RequestMapping("/api/customer/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;

    /**
     * POST /api/customer/ai/chat —— 对话。
     *
     * <p>首轮可不带 sessionId（后端新建并返回）。本任务固定返回 {@code replyType=TEXT}。</p>
     */
    @PostMapping("/chat")
    public R<AiChatVo> chat(@Valid @RequestBody AiChatRequest request) {
        return R.ok(aiChatService.chat(request));
    }
}
