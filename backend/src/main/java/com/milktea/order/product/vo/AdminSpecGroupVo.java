package com.milktea.order.product.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 商家端规格组条目（T22，LLD 3.5.2 {@code GET /api/admin/spec-groups}）：
 * 四组系统模板（CUP_SIZE / TEMPERATURE / SWEETNESS / TOPPING），含全部选项（含停用项）。
 */
@Data
public class AdminSpecGroupVo {

    private Long id;

    /** 组编码（强约束，顾客端菜单按此渲染单选/多选） */
    private String code;

    private String name;

    /** true=多选（加料），false=单选（杯型/温度/甜度） */
    private Boolean multiSelect;

    private Integer sortOrder;

    /** 1=启用 0=停用 */
    private Integer enabled;

    private List<AdminSpecOptionVo> options = new ArrayList<>();
}
