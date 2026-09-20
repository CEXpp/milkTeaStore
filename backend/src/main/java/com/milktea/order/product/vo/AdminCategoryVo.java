package com.milktea.order.product.vo;

import lombok.Data;

/**
 * 商家端分类条目（T22，LLD 3.5.1 {@code GET /api/admin/categories}）：
 * 含每组商品数，供列表展示与「删除前置校验」提示（分类下有商品不可删）。
 */
@Data
public class AdminCategoryVo {

    private Long id;
    private String name;
    private Integer sortOrder;

    /** 该分类下的商品数（含下架商品——有商品即不可删除） */
    private Long productCount;
}
