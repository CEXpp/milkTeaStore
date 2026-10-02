package com.milktea.order.forecast.service;

import com.milktea.order.forecast.config.ForecastProperties;
import com.milktea.order.forecast.mapper.DemandForecastMapper;
import com.milktea.order.forecast.vo.DemandForecastVo;
import com.milktea.order.statistics.service.StatsService;
import com.milktea.order.statistics.vo.StatsRankingVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 爆单预测与备料建议（T69，D3b · W09）。
 *
 * <p><b>预测只给建议，绝不自动执行</b>（任务卡关键纪律）：本服务<b>不写任何表</b>、
 * 不调用暂停接单、不改价、不上下架。「建议权在系统，决定权在人」在这里的落地方式是
 * 本类没有任何写路径——不是靠「我们记得别自动执行」。</p>
 *
 * <p><b>为什么用同星期同时段而非复杂模型</b>：单店每天只有几十单，复杂模型在这点样本上
 * 学到的是噪声；而「上周三下午三点大概几单」是店长自己算账时就在用的方法，
 * 预测偏了店长一眼能看出原因（比如那天下了雨）。</p>
 *
 * <p><b>建议里不出现库存数量</b>（验收项）：系统没有库存模型，说出「备 20 杯珍珠」
 * 就是编造。只说「提前备好哪些品类」。</p>
 */
@Slf4j
@Service
public class DemandForecastService {

    private final DemandForecastMapper forecastMapper;
    private final StatsService statsService;
    private final ForecastProperties properties;
    private final PrepAdviceNarrator narrator;

    private final Clock clock;

    public DemandForecastService(DemandForecastMapper forecastMapper,
                                 StatsService statsService,
                                 ForecastProperties properties,
                                 PrepAdviceNarrator narrator,
                                 @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.forecastMapper = forecastMapper;
        this.statsService = statsService;
        this.properties = properties;
        this.narrator = narrator;
        this.clock = Clock.system(ZoneId.of(timeZone));
    }

    /**
     * 预测未来一个窗口的单量并给出建议。
     *
     * @return 预测与建议；预测未启用时返回 {@link DemandForecastVo#disabled(int)}
     */
    public DemandForecastVo forecast() {
        int window = properties.windowMinutesOrDefault();
        if (!properties.enabledOrDefault()) {
            return DemandForecastVo.disabled(window);
        }

        LocalTime now = LocalTime.now(clock);
        LocalDate today = LocalDate.now(clock);
        // 预测「接下来这一小段」：起点取当前时刻，不取整点——
        // 店长关心的是「从现在起这 15 分钟」，不是「15:00~15:15 这一段」
        LocalTime slotStart = now.withSecond(0).withNano(0);
        LocalTime slotEnd = slotStart.plusMinutes(window);

        int dayOfWeek = mysqlDayOfWeek(today.getDayOfWeek());
        LocalDate since = today.minusWeeks(properties.lookbackWeeksOrDefault());

        Map<String, Object> weekly = forecastMapper.selectSlotAverage(
                dayOfWeek, slotStart, slotEnd, since, today);
        int sampleDays = (int) asLong(weekly == null ? null : weekly.get("sampleDays"));
        double avg = asDouble(weekly == null ? null : weekly.get("avgOrders"));
        String basis;

        if (sampleDays >= properties.minSampleDaysOrDefault()) {
            basis = "依据近 " + properties.lookbackWeeksOrDefault() + " 周内 " + sampleDays
                    + " 个同为「" + weekLabel(today.getDayOfWeek()) + "·" + slotStart + "~" + slotEnd
                    + "」的时段，日均 " + fmt(avg) + " 单";
        } else {
            // 同星期样本太少：退回「最近这段日子同一个点」更稳，
            // 否则「上上周三恰好下雨」这种个案会被当成预测值
            LocalDate recentSince = today.minusDays(14);
            Map<String, Object> recent = forecastMapper.selectRecentSlotAverage(
                    slotStart, slotEnd, recentSince, today);
            sampleDays = (int) asLong(recent == null ? null : recent.get("sampleDays"));
            avg = asDouble(recent == null ? null : recent.get("avgOrders"));
            basis = sampleDays == 0
                    ? "历史数据不足（近 14 天该时段没有成交），暂按 0 单估计"
                    : "同星期样本偏少（仅 " + sampleDays + " 天），改用近 14 天同时段 "
                            + slotStart + "~" + slotEnd + " 的日均 " + fmt(avg) + " 单";
        }

        int predicted = (int) Math.round(avg);
        String level = levelOf(predicted);
        List<DemandForecastVo.TopItem> topProducts = recentTopProducts();

        String advice = null;
        boolean degraded = false;
        if (!DemandForecastVo.LEVEL_NORMAL.equals(level)) {
            // 只有非正常时才生成建议：正常时段也给一段「建议备料」是噪声，
            // 店长会很快学会无视它——那样真爆单时也不会看
            String fallback = templateAdvice(level, predicted, window, topProducts);
            try {
                advice = narrator.advise(
                        buildFacts(level, predicted, window, slotStart, slotEnd, basis, topProducts));
            } catch (Exception e) {
                degraded = true;
                advice = fallback;
                log.warn("[T69] 备料建议表述层不可用，退化模板文本：{}", e.getMessage());
            }
        }

        return new DemandForecastVo(true, window, predicted, level, levelLabel(level),
                basis, sampleDays, advice, degraded, topProducts);
    }

    /** 分级：阈值 ≤0 表示关闭该级提示（可配可关）。 */
    private String levelOf(int predicted) {
        int overload = properties.overloadOrdersOrZero();
        if (overload > 0 && predicted >= overload) {
            return DemandForecastVo.LEVEL_OVERLOAD;
        }
        int busy = properties.busyOrdersOrZero();
        if (busy > 0 && predicted >= busy) {
            return DemandForecastVo.LEVEL_BUSY;
        }
        return DemandForecastVo.LEVEL_NORMAL;
    }

    /**
     * 近期销量结构（近 7 日 TOP 5）：建议里「备什么」的依据。
     *
     * <p>复用 {@code StatsService.ranking} 而不是自取一遍——口径与报表同源，
     * 店长看到的「卖得最好」在两边是同一个答案。</p>
     */
    private List<DemandForecastVo.TopItem> recentTopProducts() {
        List<DemandForecastVo.TopItem> items = new ArrayList<>();
        try {
            List<StatsRankingVo> ranking = statsService.ranking("7d", 5);
            for (StatsRankingVo row : ranking) {
                items.add(new DemandForecastVo.TopItem(row.productName(), row.cupCount()));
            }
        } catch (Exception e) {
            log.warn("[T69] 近期销量结构取数失败，建议退化为通用文本：{}", e.getMessage());
        }
        return items;
    }

    /**
     * 交给表述层的事实（只给结论，不给可自由组合的明细）。
     *
     * <p>明确写出「不要给出数量」——提示词里也说了，这里再强调一次是因为
     * 「备 20 杯珍珠」是最容易被模型顺手补出来的句子，而系统根本没有库存模型。</p>
     */
    private String buildFacts(String level, int predicted, int window, LocalTime slotStart,
                              LocalTime slotEnd, String basis, List<DemandForecastVo.TopItem> topProducts) {
        StringBuilder text = new StringBuilder();
        text.append("预测窗口：").append(slotStart).append(" ~ ").append(slotEnd)
                .append("（未来 ").append(window).append(" 分钟）\n")
                .append("预测单量：").append(predicted).append(" 单\n")
                .append("级别：").append(levelLabel(level)).append('\n')
                .append("预测依据：").append(basis).append('\n');
        if (!topProducts.isEmpty()) {
            text.append("近 7 日销量结构（TOP）：");
            for (DemandForecastVo.TopItem item : topProducts) {
                text.append(item.productName()).append(' ').append(item.cupCount()).append(" 杯、");
            }
            text.setLength(text.length() - 1);
            text.append('\n');
        }
        text.append("请只给「提前准备哪些品类」的定性建议，不要给出任何数量")
                .append("（系统没有库存数据，写数量就是编造）。");
        return text.toString();
    }

    /**
     * 模板建议（AI 不可用时的降级文本）。
     *
     * <p>只点名品类、不给数量、不提任何可执行动作——降级也要守住纪律。</p>
     */
    private String templateAdvice(String level, int predicted, int window,
                                  List<DemandForecastVo.TopItem> topProducts) {
        StringBuilder text = new StringBuilder();
        text.append("预计未来 ").append(window).append(" 分钟约 ").append(predicted).append(" 单");
        if (DemandForecastVo.LEVEL_OVERLOAD.equals(level)) {
            text.append("，可能爆单。建议提前备料，必要时由你手动决定是否暂停接单（系统不会自动暂停）。");
        } else {
            text.append("，偏忙。建议提前备好常用原料。");
        }
        if (!topProducts.isEmpty()) {
            text.append("近期主力：");
            List<String> names = new ArrayList<>(3);
            for (int i = 0; i < Math.min(3, topProducts.size()); i++) {
                names.add(topProducts.get(i).productName());
            }
            text.append(String.join("、", names)).append('。');
        }
        return text.toString();
    }

    /** MySQL {@code DAYOFWEEK} 语义：1=周日 … 7=周六（与 java.time 的 MONDAY=1 不同，需转换）。 */
    private static int mysqlDayOfWeek(DayOfWeek dayOfWeek) {
        return dayOfWeek.getValue() % 7 + 1;
    }

    private static String weekLabel(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }

    private static String levelLabel(String level) {
        return switch (level) {
            case DemandForecastVo.LEVEL_OVERLOAD -> "可能爆单";
            case DemandForecastVo.LEVEL_BUSY -> "偏忙";
            default -> "正常";
        };
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(value.toString());
    }

    private static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value == null ? 0d : Double.parseDouble(value.toString());
    }

    private static String fmt(double value) {
        return java.math.BigDecimal.valueOf(value)
                .setScale(1, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}