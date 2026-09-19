package com.milktea.order.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 商家作废请求（T14，LLD 3.5 {@code POST /api/admin/orders/{id}/void}）：reason 必填，
 * 缺失由 @Valid 校验统一返回 1001；作废仅限 PAID（未开始制作）订单。
 */
@Data
public class VoidRequest {

    /** 作废原因（顾客临时不要了 / 备料不足等），写入 orders.void_reason 并进统计留痕。 */
    @NotBlank(message = "作废原因不能为空")
    private String reason;
}
