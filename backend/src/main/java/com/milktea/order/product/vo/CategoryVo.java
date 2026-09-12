package com.milktea.order.product.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class CategoryVo implements Serializable {

    private Long id;

    private String name;

    private Integer sortOrder;

    private List<ProductVo> products;
}
