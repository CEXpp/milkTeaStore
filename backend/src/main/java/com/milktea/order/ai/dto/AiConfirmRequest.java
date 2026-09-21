package com.milktea.order.ai.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * AI 草稿转订单请求（LLD 3.4 {@code POST /api/customer/ai/confirm-order}）。
 *
 * <p>快捷直付第一步：把当前会话的草稿单转成正式订单（PENDING_PAYMENT），前端随后立即调
 * pay 接口完成支付闭环。请求体只有 {@code sessionId}——商品与规格一律以服务端草稿为准，
 * 客户端无法指定价格，也无法伪造商品（SRS 约束三原则）。</p>
 *
 * @param sessionId 会话标识（必填；草稿单挂在会话上）
 */
public record AiConfirmRequest(
        @NotBlank(message = "sessionId 不能为空")
        String sessionId
) {
}
