package com.milktea.order.product.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 商品上下架请求（T21，LLD 3.5.1 {@code PUT /api/admin/products/{id}/status}）：
 * body {@code {status: 0|1}}，下架后顾客端菜单立即不再返回该商品（AC-08）。
 */
@Data
public class ProductStatusRequest {

    /** 0=下架 1=上架 */
    @NotNull(message = "上下架状态不能为空")
    @Min(value = 0, message = "状态取值 0（下架）或 1（上架）")
    @Max(value = 1, message = "状态取值 0（下架）或 1（上架）")
    private Integer status;
}
