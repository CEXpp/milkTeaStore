package com.milktea.order.order.dto;

import lombok.Data;

/**
 * 规格项快照：下单瞬间锁定，后续商品改价 / 规格改名不影响历史订单（对齐 order_item.options_snapshot）。
 */
@Data
public class OptionSnapshot {

    private Long groupId;

    private String groupName;

    private Long optionId;

    private String optionName;

    /** 两位小数价差字符串，如 "3.00" */
    private String priceDelta;

    public OptionSnapshot() {
    }

    public OptionSnapshot(Long groupId, String groupName, Long optionId, String optionName, String priceDelta) {
        this.groupId = groupId;
        this.groupName = groupName;
        this.optionId = optionId;
        this.optionName = optionName;
        this.priceDelta = priceDelta;
    }
}
