package com.milktea.order.product.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class SpecOptionVo implements Serializable {

    private Long id;

    private String name;

    /** 价差，两位小数字符串 */
    private String priceDelta;
}
