package com.milktea.order.product.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 规格选项新增 / 编辑请求（T22，LLD 3.5.2 {@code POST /api/admin/spec-groups/{gid}/options}、
 * {@code PUT /api/admin/spec-options/{id}}）。
 *
 * <p>字段全部可空以便「只改启用状态」这类局部编辑：新增时服务层强制 name/priceDelta 必填；
 * 编辑时仅覆盖非 null 字段。{@code enabled=0} 等效停用——停用选项不出现在顾客端可选集
 * （MenuService 只取 enabled=1 的选项）。</p>
 */
@Data
public class SpecOptionSaveRequest {

    @Size(max = 32, message = "规格项名称过长（最多 32 字）")
    private String name;

    /** 价差（相对商品基础价累加），可为负（如小杯减价）。 */
    @Digits(integer = 6, fraction = 2, message = "价差格式非法（最多两位小数）")
    private BigDecimal priceDelta;

    private Integer sortOrder;

    /** 1=启用 0=停用 */
    @Min(value = 0, message = "启用状态取值 0 或 1")
    @Max(value = 1, message = "启用状态取值 0 或 1")
    private Integer enabled;
}
