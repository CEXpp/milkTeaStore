package com.milktea.order.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 全局规格组模板（CUP_SIZE / TEMPERATURE / SWEETNESS / TOPPING）。
 */
@Data
@TableName("spec_group")
public class SpecGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    /** 0 单选 1 多选 */
    private Integer multiSelect;

    private Integer enabled;

    private Integer sortOrder;
}
