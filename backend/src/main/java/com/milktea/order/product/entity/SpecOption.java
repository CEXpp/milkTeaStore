package com.milktea.order.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 规格项（价差以基础价为基准累加）。
 */
@Data
@TableName("spec_option")
public class SpecOption {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long groupId;

    private String name;

    private BigDecimal priceDelta;

    private Integer enabled;

    private Integer sortOrder;
}
