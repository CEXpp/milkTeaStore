package com.milktea.order.statistics.vo;

import java.util.List;

/**
 * 对账凭证（T73，G2 · W24）。
 *
 * <p>「报表不只能<em>算给你看</em>，还能<em>随时证明给你看</em>」——本 VO 就是那个「证明」。</p>
 *
 * <p><b>关键设计</b>：{@link #items} 里同时摊开<b>报表数</b>与<b>独立复算数</b>，
 * 由 {@link #consistent} 给出结论。复算走的是另一条代码路径（逐单在 Java 里按 6.5 重算），
 * 因此「一致」是有意义的结论，而不是自己比自己。</p>
 *
 * <p><b>不含任何个人信息</b>（验收项「导出不含任何个人信息」）：只有订单号、状态、渠道、
 * 取餐码、金额、时间与判定理由——没有顾客 id、没有 openid、没有昵称、没有手机号。
 * 这些字段对「证明数字算对了」毫无用处，掺进来只会平白增加泄露面。</p>
 *
 * @param date        归属日
 * @param caliber     口径说明（逐条写明本次复算依据的 6.5 规则）
 * @param items       四个数的逐项比对结果
 * @param consistent  四个数是否全部一致
 * @param conclusion  结论文案（一致 / 不一致及差异定位）
 * @param included    纳入明细（逐条带判定理由）
 * @param excluded    排除明细（逐条带排除理由）
 * @param generatedAt 生成时间
 */
public record ReconcileCertificateVo(
        String date,
        List<String> caliber,
        List<Item> items,
        boolean consistent,
        String conclusion,
        List<Row> included,
        List<Row> excluded,
        String generatedAt) {

    /**
     * 一个数的比对结果。
     *
     * @param name     指标名（营业额 / 订单数 / 杯数 / 退款额）
     * @param reported 报表数
     * @param recomputed 独立复算数
     * @param match    是否一致
     */
    public record Item(String name, String reported, String recomputed, boolean match) {
    }

    /**
     * 明细一行。
     *
     * @param orderNo    订单号
     * @param status     状态
     * @param source     渠道
     * @param amount     实付金额
     * @param voidReason 作废原因
     * @param reason     纳入 / 排除的判定理由
     */
    public record Row(String orderNo, String status, String source, String amount,
                      String voidReason, String reason) {
    }
}