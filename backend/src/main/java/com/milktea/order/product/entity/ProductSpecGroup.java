package com.milktea.order.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 商品适用的规格组。
 */
@Data
@TableName("product_spec_group")
public class ProductSpecGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    private Long groupId;
}
