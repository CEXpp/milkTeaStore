package com.milktea.order.shop.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 门店营业状态（LLD 3.3 GET /api/customer/shop-status）。
 */
@Data
public class ShopStatusVo implements Serializable {

    private Boolean paused;

    private String notice;
}
