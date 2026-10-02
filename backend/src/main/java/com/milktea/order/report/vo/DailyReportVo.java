package com.milktea.order.report.vo;

import java.util.List;

/**
 * 每日经营日报（T68，W08）。
 *
 * <p><b>分层结构决定「AI 只做表述层」</b>：{@link #metrics} 与 {@link #anomalies} 是
 * <b>先算好的</b>确定值，{@link #narrative} 才是 AI 把前者讲成人话的产物。
 * 模型拿不到原始表、也没有任何工具，只负责措辞——因此它<b>无法自由找规律</b>，
 * 也就无法编造趋势（任务卡关键设计）。</p>
 *
 * <p>验收对照：「日报中每个数字可由统计接口复算」→ {@link #metrics} 全部来自
 * {@code StatsService}；「无异常时明确说今日无异常，不编造」→
 * {@link #anomalies} 为空时 {@link #noAnomaly} 为 true；「AI 不可用时降级为纯数据版」→
 * {@link #degraded} 为 true 且 {@link #metrics} 照常返回。</p>
 *
 * @param date       归属日
 * @param metrics    算好的指标（含与近 7 日同期对比）
 * @param anomalies  异常项；空列表表示当日无异常
 * @param noAnomaly  是否明确「今日无异常」（anomalies 为空时为 true）
 * @param narrative  AI 表述层文案；降级或未生成时为 null
 * @param degraded   是否降级（AI 不可用，仅纯数据版）
 * @param generatedAt 生成时间
 */
public record DailyReportVo(
        String date,
        Metrics metrics,
        List<Anomaly> anomalies,
        boolean noAnomaly,
        String narrative,
        boolean degraded,
        String generatedAt) {

    /**
     * 当日指标 + 与近 7 日同期均值的对比。
     *
     * <p><b>对比基准刻意用「近 7 日同期均值」而非昨天</b>：单日对单日波动太大，
     * 周二对比周一得出的「暴跌」多半只是星期的节奏差异。</p>
     *
     * @param revenue              当日营业额（有效已支付，扣作废）
     * @param orderCount           当日订单数（含作废，不含超时关闭）
     * @param cupCount             当日杯数
     * @param refundAmount         当日退款额
     * @param refundRatioPercent   退款率（退款额 / (营业额+退款额)，百分比两位小数）
     * @param avgRevenue           近 7 日同期营业额均值
     * @param avgOrderCount        近 7 日同期订单数均值
     * @param avgCupCount          近 7 日同期杯数均值
     * @param revenueChangePercent 营业额较均值的变化（百分比；均值为 0 时为 null，不做除零）
     * @param orderChangePercent   订单数较均值的变化（同上）
     * @param channels             渠道结构（当日）
     * @param topProducts          当日 TOP 商品
     */
    public record Metrics(
            String revenue,
            long orderCount,
            long cupCount,
            String refundAmount,
            String refundRatioPercent,
            String avgRevenue,
            long avgOrderCount,
            long avgCupCount,
            Double revenueChangePercent,
            Double orderChangePercent,
            int sampleDays,
            List<Channel> channels,
            List<TopProduct> topProducts) {
    }

    /**
     * 渠道结构。
     *
     * @param source     渠道
     * @param orderCount 单量
     * @param amount     金额
     * @param sharePercent 单量占比（百分比两位小数）
     */
    public record Channel(String source, long orderCount, String amount, String sharePercent) {
    }

    /**
     * TOP 商品。
     *
     * @param productName 商品名（快照名）
     * @param cupCount    杯数
     * @param amount      金额
     */
    public record TopProduct(String productName, long cupCount, String amount) {
    }

    /**
     * 异常项（由确定性阈值判定，不由模型发现）。
     *
     * @param code   异常代号（渠道占比骤降 / 退款异常 / 营业额下滑 …）
     * @param label  中文短标签
     * @param detail 具体数值说明（含对比基准，便于店长判断严重性）
     */
    public record Anomaly(String code, String label, String detail) {
    }
}