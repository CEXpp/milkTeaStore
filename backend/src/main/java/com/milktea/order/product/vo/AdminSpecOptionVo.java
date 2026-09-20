package com.milktea.order.product.vo;

import lombok.Data;

/**
 * 商家端规格选项条目（T22，LLD 3.5.2 {@code GET /api/admin/spec-groups}）：
 * 管理页需要看到停用项，故与顾客端菜单 VO 不同——enabled 原值返回，价差为两位小数字符串。
 */
@Data
public class AdminSpecOptionVo {

    private Long id;
    private String name;

    /** 价差（两位小数字符串） */
    private String priceDelta;

    private Integer sortOrder;

    /** 1=启用 0=停用 */
    private Integer enabled;
}
