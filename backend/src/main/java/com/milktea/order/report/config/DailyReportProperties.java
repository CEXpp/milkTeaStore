package com.milktea.order.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 日报阈值配置（T68）。
 *
 * <p><b>阈值全部可配</b>：不同门店对「异常」的容忍度不同（街边店和商场店日销差一个量级），
 * 硬编码会逼着改代码。<b>0 或负数表示关闭该条检测</b>——不想被提醒就应该能关掉。</p>
 *
 * @param closingHour         打烊生成时刻（0-23），默认 22 点
 * @param revenueDropPercent  营业额较近 7 日同期均值下降超过该百分比即报异常
 * @param refundRatioPercent  退款率超过该百分比即报异常
 * @param channelDropPoints   某渠道单量占比相对近 7 日同期下降超过该百分点即报异常
 */
@ConfigurationProperties(prefix = "report")
public record DailyReportProperties(
        Integer closingHour,
        BigDecimal revenueDropPercent,
        BigDecimal refundRatioPercent,
        BigDecimal channelDropPoints) {

    /** 打烊时刻默认 22 点。 */
    public int closingHourOrDefault() {
        return closingHour == null ? 22 : Math.min(Math.max(closingHour, 0), 23);
    }

    /** 营业额下滑阈值默认 30%。 */
    public BigDecimal revenueDropOrDefault() {
        return revenueDropPercent == null ? new BigDecimal("30") : revenueDropPercent;
    }

    /** 退款率阈值默认 10%。 */
    public BigDecimal refundRatioOrDefault() {
        return refundRatioPercent == null ? new BigDecimal("10") : refundRatioPercent;
    }

    /** 渠道占比下降阈值默认 20 个百分点。 */
    public BigDecimal channelDropOrDefault() {
        return channelDropPoints == null ? new BigDecimal("20") : channelDropPoints;
    }
}