package com.milktea.order.shop.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 门店暂停接单开关响应（T23，LLD 3.5.5）：{@code {paused: true}}。
 */
@Data
@AllArgsConstructor
public class ShopPauseVo {

    /** 当前暂停状态：true=暂停接单 */
    private Boolean paused;
}
