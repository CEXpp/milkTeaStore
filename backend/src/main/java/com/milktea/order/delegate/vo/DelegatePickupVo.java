package com.milktea.order.delegate.vo;

import java.util.List;

/**
 * 代取凭证页数据（T65，W15）。
 *
 * <p><b>本 VO 是「代取人能看到的全部」</b>——刻意不含金额、支付渠道、交易号、
 * 顾客身份、备注、其他订单中的任何一个字段。这不是「前端选择不显示」，
 * 而是<b>后端根本没有把这些字段查出来</b>：泄露面从渲染层前移到取数层，
 * 将来有人给这个页面加个调试开关也翻不出金额。</p>
 *
 * <p>验收项逐条对照：</p>
 * <ul>
 *   <li>「代取人仅能看到取餐码与商品概要」→ 只有 {@code pickupCode} + {@code items}；</li>
 *   <li>「看不到金额 / 支付信息 / 历史订单」→ 三个都没有；</li>
 *   <li>「用一次后失效」→ 由 {@link #usedUp} 明示，页面可直接提示对方重开；</li>
 * </ul>
 *
 * @param orderNo    订单号（供代取人到店报号核对）
 * @param pickupCode 取餐码——<b>大号展示</b>，是代取人唯一真正需要的东西
 * @param status     订单状态（代取人只需知道「还能不能取」）
 * @param items      商品概要（商品名 × 杯数+ 规格摘要；<b>无单价</b>）
 * @param expiresAt令牌到期时间
 * @param usedUp     本次访问后令牌即失效（恒为 true，见 service 注释）
 * @param revokedByOwner 是否已被原主撤销（页面据此提示「对方已收回」）
 */
public record DelegatePickupVo(
        String orderNo,
        String pickupCode,
        String status,
        List<Item> items,
        String expiresAt,
        boolean usedUp,
        boolean revokedByOwner) {

    /**
     * 商品概要。
     *
     * @param productName 商品名快照
     * @param quantity    杯数
     * @param specSummary 规格一行摘要（大杯 · 少冰 · 五分糖 · 珍珠）
     */
    public record Item(String productName, int quantity, String specSummary) {
    }
}