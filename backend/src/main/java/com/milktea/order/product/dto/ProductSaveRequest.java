package com.milktea.order.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 新建 / 编辑商品请求（T21，LLD 3.5.1 {@code POST|PUT /api/admin/products}）。
 *
 * <p>字段与 LLD 契约逐项对齐：{@code name/categoryId/basePrice/description/imageKey/sortOrder/specGroupIds[]}。
 * {@code specGroupIds} 为全量覆盖语义（缺省 null 表示本次不改动适用规格组）；
 * 价格修改不影响历史订单——下单即写快照（LLD 4.5）。</p>
 */
@Data
public class ProductSaveRequest {

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 64, message = "商品名称过长（最多 64 字）")
    private String name;

    @NotNull(message = "商品分类不能为空")
    private Long categoryId;

    @NotNull(message = "基础价不能为空")
    @DecimalMin(value = "0.00", message = "基础价不能为负")
    @Digits(integer = 8, fraction = 2, message = "基础价格式非法（最多两位小数）")
    private BigDecimal basePrice;

    @Size(max = 255, message = "商品描述过长（最多 255 字）")
    private String description;

    /** MinIO 对象 key：由 {@code POST /api/admin/upload} 返回，前端只回填 key（不存完整 URL）。 */
    @Size(max = 255, message = "图片 key 过长")
    private String imageKey;

    private Integer sortOrder;

    /** 适用规格组 id 列表（默认全选由前端给出；空列表表示该商品不提供规格选择）。 */
    private List<Long> specGroupIds;
}
