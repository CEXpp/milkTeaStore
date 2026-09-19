package com.milktea.order.product.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 商家端商品条目（T21，LLD 3.5.1 {@code GET /api/admin/products}）：
 * 含图片 key、价格、状态与适用规格组 id 列表；金额统一两位小数字符串（LLD 3.1）。
 */
@Data
public class AdminProductVo {

    private Long id;
    private String name;
    private Long categoryId;

    /** 基础价（两位小数字符串） */
    private String basePrice;

    private String description;

    /** MinIO 对象 key；图片展示走 {@code /api/files/{key}} 代理 */
    private String imageKey;

    /** 1=上架 0=下架 */
    private Integer status;

    private Integer sortOrder;

    /** 适用规格组 id 列表 */
    private List<Long> specGroupIds = new ArrayList<>();
}
