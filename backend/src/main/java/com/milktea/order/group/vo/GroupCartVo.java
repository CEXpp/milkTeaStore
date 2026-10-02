package com.milktea.order.group.vo;

import java.util.List;

/**
 * 拼单池视图（T63，W13）。
 *
 * <p><b>金额永远由后端现算</b>（验收项）：{@code members[].items[].unitPrice} 与
 * {@code totalAmount} 都在每次读取时经计价引擎重算，客户端提交的任何金额都不被采信。
 * 这也让「加料期间改价」在冻结瞬间自动生效，无需同步草稿里的旧价。</p>
 *
 * @param groupUuid  拼单标识（加入 / 冻结 / 支付都凭它）
 * @param ownerId    发起人 customer id（前端据此决定是否显示「冻结并支付」）
 * @param status阶段（OPEN / FROZEN / CONVERTING / CONVERTED / EXPIRED）
 * @param expired    是否已到期（到点仍未支付即不可转正式单）
 * @param canFreeze  当前身份能否冻结：仅发起人且处于 OPEN 且未到期
 * @param expiresAt  截止时间
 * @param totalAmount合计金额（两位小数字符串，后端现算）
 * @param cupCount   总杯数
 * @param invalidTip 失效原因提示（如「珍珠奶茶已下架」）；无失效项时为 null
 * @param members    各成员的选品与实时计价
 */
public record GroupCartVo(
        String groupUuid,
        Long ownerId,
        String status,
        boolean expired,
        boolean canFreeze,
        String expiresAt,
        String totalAmount,
        int cupCount,
        String invalidTip,
        List<Member> members) {

    /**
     * 成员选品。
     *
     * @param customerId 参与者 id
     * @param tag        成员标识（可能为 null）
     * @param items      逐项实时计价（无法计价的项会被剔除并计入 invalidTip）
     */
    public record Member(Long customerId, String tag, List<Item> items) {
    }

    /**
     * 一杯（含规格与金额）。
     *
     * @param productId商品 id
     * @param productName商品名快照（此刻的名字）
     * @param quantity 数量
     * @param specText 规格一行摘要（大杯 · 少冰 · 五分糖 · 珍珠）
     * @param unitPrice单价（两位小数字符串）
     * @param itemAmount小计（两位小数字符串）
     */
    public record Item(Long productId, String productName, int quantity,
                       String specText, String unitPrice, String itemAmount) {
    }
}