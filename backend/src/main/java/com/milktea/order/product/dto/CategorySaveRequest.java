package com.milktea.order.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 / 编辑分类请求（T22，LLD 3.5.1 {@code POST|PUT /api/admin/categories}）。
 */
@Data
public class CategorySaveRequest {

    @NotBlank(message = "分类名称不能为空")
    @Size(max = 32, message = "分类名称过长（最多 32 字）")
    private String name;

    /** 排序值（升序展示）；缺省按现有值或 0 处理。 */
    private Integer sortOrder;
}
