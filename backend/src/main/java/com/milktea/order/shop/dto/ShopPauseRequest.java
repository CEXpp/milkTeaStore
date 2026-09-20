package com.milktea.order.shop.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 门店暂停接单开关请求（T23，LLD 3.5.5 {@code PUT /api/admin/shop/pause}）。
 *
 * <p>开启后顾客端下单接口返回 1006；进行中订单不受影响（看板照常流转）。
 * notice 为顾客端展示的暂停提示语（选填，缺省保留库中原值）。</p>
 */
@Data
public class ShopPauseRequest {

    @NotNull(message = "暂停状态不能为空")
    private Boolean paused;

    @Size(max = 64, message = "提示语过长（最多 64 字）")
    private String notice;
}
