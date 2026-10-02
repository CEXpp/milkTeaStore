package com.milktea.order.report.service;

import com.milktea.order.report.config.DailyReportProperties;
import com.milktea.order.report.vo.DailyReportVo;
import com.milktea.order.statistics.service.StatsService;
import com.milktea.order.statistics.vo.StatsChannelVo;
import com.milktea.order.statistics.vo.StatsRankingVo;
import com.milktea.order.statistics.vo.StatsSummaryVo;
import com.milktea.order.statistics.vo.StatsTrendVo;
import com.milktea.order.common.util.MoneyUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 日报的<b>计算层</b>（T68，W08）。
 *
 * <p><b>「AI 只做表述层，不做计算层」的落地点就是这个类</b>（任务卡关键设计）：
 * 所有数字与异常判定在<b>调用模型之前</b>就已算完，模型只拿到结论性的文本，
 * 没有原始表、没有工具、也没有自行取数的能力。因此它<b>无法自由找规律</b>，
 * 也就无法编造趋势。</p>
 *
 * <p>异常判定全部是<b>确定性阈值</b>而不是「让模型看看有没有异常」——
 * 后者会稳定地编出异常（模型有讨好提问者的倾向，问「有异常吗」它几乎总会找出几条）。
 * 无异常时返回空列表，上层据此明确答「今日无异常」。</p>
 *
 * <p>对比基准取<b>近 7 日同期均值</b>而非昨天：单日对单日波动太大，
 * 周二对比周一得出的「暴跌」多半只是星期的节奏差异。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReportCalculator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 近 7 日对照窗口（不含当日）。 */
    private static final int BASELINE_DAYS = 7;

    private final StatsService statsService;
    private final DailyReportProperties properties;

    /**
     * 算出某日的指标与异常项。
     *
     * @param date 归属日
     * @return 指标 + 异常项（异常为空列表表示无异常）
     */
    public Computed compute(LocalDate date) {
        String day = date.format(DATE_FMT);
        StatsSummaryVo summary = statsService.summary(day);

        // 对照窗口：当日之前 7 天（含当日会让「与均值对比」被自己拉平）
        List<StatsTrendVo> window = baselineWindow(date);
        BigDecimal avgRevenue = averageAmount(window);
        long avgOrderCount = Math.round(averageCount(window));
        long avgCupCount = Math.round(averageCups(date, window));

        long revenue = toLong(summary.totalAmount());
        long refund = toLong(summary.refundAmount());
        // 退款率分母用「营业额 + 退款额」，即未扣退款前的实付总额——
        // 用扣减后的营业额当分母会让退款率随退款额反向漂移
        long gross = revenue + refund;
        String refundRatio = gross <= 0
                ? "0.00"
                : BigDecimal.valueOf(refund * 100.0 / gross).setScale(2, RoundingMode.HALF_UP).toPlainString();

        List<DailyReportVo.Channel> channels = toChannels(summary);
        List<DailyReportVo.TopProduct> topProducts = toTopProducts(day);

        DailyReportVo.Metrics metrics = new DailyReportVo.Metrics(
                summary.totalAmount(),
                summary.orderCount(),
                summary.cupCount(),
                summary.refundAmount(),
                refundRatio,
                MoneyUtils.format(avgRevenue),
                avgOrderCount,
                avgCupCount,
                changePercent(revenue, avgRevenue),
                changePercent(summary.orderCount(), BigDecimal.valueOf(avgOrderCount)),
                window.size(),
                channels,
                topProducts);

        return new Computed(metrics, detectAnomalies(metrics, refundRatio));
    }

    /**
     * 异常判定（确定性阈值）。
     *
     * <p>阈值取 0 或负数表示关闭该条检测——不想被提醒就应该能关掉。</p>
     */
    private List<DailyReportVo.Anomaly> detectAnomalies(DailyReportVo.Metrics metrics, String refundRatio) {
        List<DailyReportVo.Anomaly> anomalies = new ArrayList<>();

        BigDecimal dropThreshold = properties.revenueDropOrDefault();
        if (dropThreshold.signum() > 0
                && metrics.revenueChangePercent() != null
                && metrics.revenueChangePercent() < -dropThreshold.doubleValue()) {
            anomalies.add(new DailyReportVo.Anomaly(
                    "REVENUE_DROP",
                    "营业额下滑",
                    "当日营业额 " + metrics.revenue() + " 元，较近 " + metrics.sampleDays()
                            + " 日同期均值 " + metrics.avgRevenue() + " 元下降 "
                            + fmt(Math.abs(metrics.revenueChangePercent())) + "%"
                            + "（阈值 " + dropThreshold.toPlainString() + "%）"));
        }

        BigDecimal refundThreshold = properties.refundRatioOrDefault();
        if (refundThreshold.signum() > 0
                && new BigDecimal(refundRatio).compareTo(refundThreshold) > 0) {
            anomalies.add(new DailyReportVo.Anomaly(
                    "REFUND_HIGH",
                    "退款异常",
                    "当日退款额 " + metrics.refundAmount() + " 元，退款率 " + refundRatio
                            + "% 高于阈值 " + refundThreshold.toPlainString() + "%"));
        }

        BigDecimal channelThreshold = properties.channelDropOrDefault();
        if (channelThreshold.signum() > 0) {
            anomalies.addAll(channelAnomalies(metrics, channelThreshold));
        }
        return anomalies;
    }

    /**
     * 渠道结构骤降检测：把当日各渠道的<b>单量占比</b>与近 7 日同期占比对比。
     *
     * <p>比「单量绝对值下降」更稳：当日总量整体下滑时，各渠道绝对量都会掉，
     * 但那不是渠道结构问题而是客流问题（营业额下滑那条已经覆盖）。
     * 只有某渠道<b>占比</b>显著掉队，才说明该渠道本身出了事。</p>
     */
    private List<DailyReportVo.Anomaly> channelAnomalies(DailyReportVo.Metrics metrics, BigDecimal threshold) {
        List<DailyReportVo.Anomaly> anomalies = new ArrayList<>();
        long todayTotal = metrics.channels().stream().mapToLong(DailyReportVo.Channel::orderCount).sum();
        if (todayTotal <= 0) {
            return anomalies;
        }
        for (DailyReportVo.Channel channel : metrics.channels()) {
            double todayShare = channel.orderCount() * 100.0 / todayTotal;
            Double baselineShare = baselineChannelShare(channel.source());
            if (baselineShare == null) {
                continue;
            }
            double drop = baselineShare - todayShare;
            if (drop > threshold.doubleValue()) {
                anomalies.add(new DailyReportVo.Anomaly(
                        "CHANNEL_DROP",
                        "渠道占比骤降",
                        labelOf(channel.source()) + "渠道单量占比 " + fmt(todayShare) + "%，较近 "
                                + BASELINE_DAYS + " 日同期 " + fmt(baselineShare) + "% 下降 "
                                + fmt(drop) + " 个百分点（阈值 " + threshold.toPlainString() + "）"));
            }
        }
        return anomalies;
    }

    /** 某渠道在近 7 日的单量占比均值；无样本时返回 null（不参与判定，避免新渠道第一天就误报）。 */
    private Double baselineChannelShare(String source) {
        LocalDate today = LocalDate.now();
        long sourceOrders = 0;
        long totalOrders = 0;
        for (int i = 1; i <= BASELINE_DAYS; i++) {
            StatsSummaryVo summary = statsService.summary(today.minusDays(i).format(DATE_FMT));
            totalOrders += summary.orderCount();
            if (summary.channel() != null) {
                for (StatsChannelVo channel : summary.channel()) {
                    if (source.equals(channel.source())) {
                        sourceOrders += channel.orderCount();
                    }
                }
            }
        }
        return totalOrders <= 0 ? null : sourceOrders * 100.0 / totalOrders;
    }

    /** 当日之前 7 日的趋势（右开区间避开当日）。 */
    private List<StatsTrendVo> baselineWindow(LocalDate date) {
        List<StatsTrendVo> all = statsService.trend(BASELINE_DAYS + 1);
        List<StatsTrendVo> window = new ArrayList<>(BASELINE_DAYS);
        String today = date.format(DATE_FMT);
        for (StatsTrendVo point : all) {
            if (!today.equals(point.date())) {
                window.add(point);
            }
        }
        return window;
    }

    private BigDecimal averageAmount(List<StatsTrendVo> window) {
        if (window.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (StatsTrendVo point : window) {
            sum = sum.add(new BigDecimal(point.amount() == null ? "0" : point.amount()));
        }
        return sum.divide(BigDecimal.valueOf(window.size()), 2, RoundingMode.HALF_UP);
    }

    private double averageCount(List<StatsTrendVo> window) {
        if (window.isEmpty()) {
            return 0;
        }
        long sum = 0;
        for (StatsTrendVo point : window) {
            sum += point.orderCount();
        }
        return (double) sum / window.size();
    }

    /**
     * 近 7 日杯数均值。
     *
     * <p>趋势接口只给单量不给杯数，故按日回查概览——单店量级、只 7 次查询，
     * 不值得为它扩统计接口（扩了就要维护新的口径常量）。</p>
     */
    private double averageCups(LocalDate date, List<StatsTrendVo> window) {
        if (window.isEmpty()) {
            return 0;
        }
        long sum = 0;
        for (int i = 1; i <= BASELINE_DAYS; i++) {
            StatsSummaryVo summary = statsService.summary(date.minusDays(i).format(DATE_FMT));
            sum += summary.cupCount();
        }
        return (double) sum / BASELINE_DAYS;
    }

    private List<DailyReportVo.Channel> toChannels(StatsSummaryVo summary) {
        List<DailyReportVo.Channel> channels = new ArrayList<>();
        if (summary.channel() == null) {
            return channels;
        }
        long total = summary.channel().stream().mapToLong(StatsChannelVo::orderCount).sum();
        for (StatsChannelVo channel : summary.channel()) {
            long count = channel.orderCount();
            String share = total <= 0 ? "0.00"
                    : BigDecimal.valueOf(count * 100.0 / total).setScale(2, RoundingMode.HALF_UP).toPlainString();
            channels.add(new DailyReportVo.Channel(channel.source(), count, channel.amount(), share));
        }
        return channels;
    }

    private List<DailyReportVo.TopProduct> toTopProducts(String day) {
        List<DailyReportVo.TopProduct> tops = new ArrayList<>();
        List<StatsRankingVo> ranking = statsService.ranking("today", 5);
        for (StatsRankingVo row : ranking) {
            tops.add(new DailyReportVo.TopProduct(row.productName(), row.cupCount(), row.amount()));
        }
        return tops;
    }

    /** 变化百分比；基准为 0 时返回 null——不做除零，也不假装算得出增长率。 */
    private Double changePercent(long current, BigDecimal baseline) {
        if (baseline == null || baseline.signum() == 0) {
            return null;
        }
        return BigDecimal.valueOf((current - baseline.doubleValue()) * 100.0)
                .divide(baseline, 1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static long toLong(String money) {
        if (!StringUtils.hasText(money)) {
            return 0;
        }
        return new BigDecimal(money).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private static String fmt(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String labelOf(String source) {
        return switch (source == null ? "" : source) {
            case "MINI_PROGRAM" -> "小程序";
            case "AI" -> "AI";
            case "COUNTER" -> "柜台";
            default -> source == null ? "未知" : source;
        };
    }

    /**
     * 计算结果：指标 + 异常项。
     *
     * @param metrics   指标
     * @param anomalies 异常项（空列表 = 今日无异常，上层须如实回答而不是编造）
     */
    public record Computed(DailyReportVo.Metrics metrics, List<DailyReportVo.Anomaly> anomalies) {

        public boolean hasAnomaly() {
            return !anomalies.isEmpty();
        }
    }
}