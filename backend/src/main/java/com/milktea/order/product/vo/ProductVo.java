package com.milktea.order.product.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ProductVo implements Serializable {

    private Long id;

    private String name;

    private String description;

    private String imageKey;

    /** 由 imageKey 拼接的文件代理路径：/api/files/{key} */
    private String imageUrl;

    /** 基础价，两位小数字符串 */
    private String basePrice;

    private List<SpecGroupVo> specGroups;
}
