package com.milktea.order.product.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 规格组编辑请求（T22，LLD 3.5.2 {@code PUT /api/admin/spec-groups/{gid}}）：名称 / 排序 / 启用。
 * 规格组为系统模板（四组），不提供删除——避免商品关联悬空。
 */
@Data
public class SpecGroupSaveRequest {

    @Size(max = 32, message = "规格组名称过长（最多 32 字）")
    private String name;

    private Integer sortOrder;

    /** 1=启用 0=停用（停用后整组不出现在顾客端菜单） */
    @Min(value = 0, message = "启用状态取值 0 或 1")
    @Max(value = 1, message = "启用状态取值 0 或 1")
    private Integer enabled;
}
