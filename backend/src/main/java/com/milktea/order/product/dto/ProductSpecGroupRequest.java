package com.milktea.order.product.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置商品适用规格组请求（T21，LLD 3.5.1 {@code PUT /api/admin/products/{id}/spec-groups}）：
 * body {@code {groupIds: [1,2,3,4]}}，全量覆盖（先删后插）。
 */
@Data
public class ProductSpecGroupRequest {

    @NotNull(message = "规格组列表不能为空（可传空数组表示清空）")
    private List<Long> groupIds = new ArrayList<>();
}
