package com.milktea.order.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品。
 */
@Data
@TableName("product")
public class Product {

    /** 上架 */
    public static final int STATUS_ON = 1;
    /** 下架 */
    public static final int STATUS_OFF = 0;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long categoryId;

    private String name;

    private String description;

    /** MinIO 对象 key（非完整 URL） */
    private String imageKey;

    private BigDecimal basePrice;

    /** 1 上架 0 下架 */
    private Integer status;

    private Integer sortOrder;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
