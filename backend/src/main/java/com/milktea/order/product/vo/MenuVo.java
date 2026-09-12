package com.milktea.order.product.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 顾客端全量菜单（LLD 3.3 GET /api/customer/menu）。
 */
@Data
public class MenuVo implements Serializable {

    /** 门店是否暂停营业（暂停时仍可浏览菜单） */
    private Boolean paused;

    private List<CategoryVo> categories;
}
