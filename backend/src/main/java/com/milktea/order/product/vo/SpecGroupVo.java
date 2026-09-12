package com.milktea.order.product.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class SpecGroupVo implements Serializable {

    private String code;

    private String name;

    private Boolean multiSelect;

    private List<SpecOptionVo> options;
}
