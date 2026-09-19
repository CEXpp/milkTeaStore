package com.milktea.order.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 创建订单请求（LLD 3.3）：小程序下单唯一入口。
 *
 * <p>items 复用与购物车 / 柜台单 / AI 草稿一致的 {@link OrderItemRequest} 计价输入；
 * 价格一律由后端计算，请求体不含任何金额字段。</p>
 */
@Data
public class OrderCreateRequest {

    @NotEmpty(message = "下单商品不能为空")
    @Valid
    private List<OrderItemRequest> items = new ArrayList<>();

    /** 口味备注（选填） */
    private String remark;
}
