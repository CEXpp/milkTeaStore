package com.milktea.order.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.report.entity.DailyReport;
import com.milktea.order.report.mapper.DailyReportMapper;
import com.milktea.order.report.vo.DailyReportVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 每日经营日报服务（T68，D5a · W08）。
 *
 * <p><b>三步走，AI 只在最后一步</b>：</p>
 * <ol>
 *   <li>{@link DailyReportCalculator} 先算指标与异常（确定性，可复算）；</li>
 *   <li>把结论拼成事实文本；</li>
 *   <li>交 {@link DailyReportNarrator} 讲成人话——<b>模型无法影响任何数字</b>。</li>
 * </ol>
 *
 * <p><b>落库而非现算</b>：日报是「打烊那一刻的快照」。第二天再看昨日日报应看到当时的结论，
 * 而不是用今天改过的商品名与价格重算出的另一套数字（与 6.3 快照同源）。
 * 重跑同一日覆盖同一行（{@code uk_report_date}）。</p>
 *
 * <p><b>降级</b>：模型不可用时 {@code degraded=1}、{@code aiText} 留空，
 * 但 <b>{@code metrics} 照常返回</b>——数据本身不依赖 AI，这是「AI 不可用时降级为纯数据版」
 * （验收项）的落地方式。</p>
 */
@Slf4j
@Service
public class DailyReportService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DailyReportCalculator calculator;
    private final DailyReportNarrator narrator;
    private final DailyReportMapper dailyReportMapper;

    private final Clock clock;

    public DailyReportService(DailyReportCalculator calculator,
                              DailyReportNarrator narrator,
                              DailyReportMapper dailyReportMapper,
                              @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.calculator = calculator;
        this.narrator = narrator;
        this.dailyReportMapper = dailyReportMapper;
        this.clock = Clock.system(ZoneId.of(timeZone));
    }

    /**
     * 生成（或重跑）某日日报并落库。
     *
     * @param date 归属日；空则取服务端「今天」
     * @return 生成结果
     * @throws BusinessException 1001 日期格式非法
     */
    @Transactional(rollbackFor = Exception.class)
    public DailyReportVo generate(String date) {
        LocalDate day = parseDate(date);
        DailyReportCalculator.Computed computed = calculator.compute(day);

        String narrative = null;
        boolean degraded = false;
        try {
            narrative = narrator.narrate(buildFacts(day, computed));
        } catch (Exception e) {
            // 模型不可用：不编造结论，退化为纯数据版（验收项）
            degraded = true;
            log.warn("[T68] 日报表述层不可用，降级为纯数据版 date={} cause={}", day, e.getMessage());
        }

        LocalDateTime now = LocalDateTime.now();
        DailyReport existing = findByDate(day);
        DailyReport entity = existing == null ? new DailyReport() : existing;
        entity.setReportDate(day);
        entity.setMetricsJson(JSON.writeValueAsString(computed.metrics()));
        entity.setAnomalyJson(JSON.writeValueAsString(computed.anomalies()));
        entity.setAiText(narrative);
        entity.setDegraded(degraded);
        entity.setGeneratedAt(now);
        entity.setUpdatedAt(now);
        if (existing == null) {
            entity.setPushState(DailyReport.PUSH_NONE);
            entity.setCreatedAt(now);
            dailyReportMapper.insert(entity);
        } else {
            dailyReportMapper.updateById(entity);
        }

        log.info("[T68] daily report generated date={} anomalies={} degraded={}",
                day, computed.anomalies().size(), degraded);
        return new DailyReportVo(
                day.format(DATE_FMT),
                computed.metrics(),
                computed.anomalies(),
                !computed.hasAnomaly(),
                narrative,
                degraded,
                now.format(DATETIME_FMT));
    }

    /**
     * 读取某日日报（商家端首页卡片）。
     *
     * <p>未生成过时返回 {@code null}，由前端提示「今日日报将在打烊后生成」——
     * 而不是现场算一份冒充「已生成的日报」（那会让「日报是快照」这件事失真）。</p>
     *
     * @param date 归属日；空则取今天
     * @throws BusinessException 1001 日期格式非法
     */
    @Transactional(readOnly = true)
    public DailyReportVo find(String date) {
        LocalDate day = parseDate(date);
        DailyReport entity = findByDate(day);
        if (entity == null) {
            return null;
        }
        DailyReportVo.Metrics metrics = readJson(entity.getMetricsJson(), DailyReportVo.Metrics.class);
        List<DailyReportVo.Anomaly> anomalies = readAnomalies(entity.getAnomalyJson());
        return new DailyReportVo(
                day.format(DATE_FMT),
                metrics,
                anomalies,
                anomalies.isEmpty(),
                entity.getAiText(),
                Boolean.TRUE.equals(entity.getDegraded()),
                entity.getGeneratedAt() == null ? null : entity.getGeneratedAt().format(DATETIME_FMT));
    }

    /**
     * 拼装交给模型的事实文本。
     *
     * <p><b>只给结论性事实</b>：不给原始表、不给可自由组合的明细，减少模型「自己找规律」的空间。
     * 异常项为空时显式写「无异常」，让模型没有理由编一条出来。</p>
     */
    private String buildFacts(LocalDate day, DailyReportCalculator.Computed computed) {
        DailyReportVo.Metrics metrics = computed.metrics();
        StringBuilder text = new StringBuilder();
        text.append("日期：").append(day.format(DATE_FMT)).append('\n')
                .append("营业额：").append(metrics.revenue()).append(" 元\n")
                .append("订单数：").append(metrics.orderCount()).append(" 单\n")
                .append("杯数：").append(metrics.cupCount()).append(" 杯\n")
                .append("退款额：").append(metrics.refundAmount()).append(" 元（退款率 ")
                .append(metrics.refundRatioPercent()).append("%）\n");

        if (metrics.sampleDays() > 0) {
            text.append("对照基准（近 ").append(metrics.sampleDays()).append(" 日同期）：营业额均值 ")
                    .append(metrics.avgRevenue()).append(" 元、订单均值 ")
                    .append(metrics.avgOrderCount()).append(" 单、杯数均值 ")
                    .append(metrics.avgCupCount()).append(" 杯\n");
            if (metrics.revenueChangePercent() != null) {
                text.append("营业额较均值变化：").append(metrics.revenueChangePercent()).append("%\n");
            }
            if (metrics.orderChangePercent() != null) {
                text.append("订单数较均值变化：").append(metrics.orderChangePercent()).append("%\n");
            }
        }

        if (!metrics.channels().isEmpty()) {
            text.append("渠道结构：");
            for (DailyReportVo.Channel channel : metrics.channels()) {
                text.append(channelLabel(channel.source())).append(' ')
                        .append(channel.orderCount()).append(" 单（占比 ")
                        .append(channel.sharePercent()).append("%） ");
            }
            text.append('\n');
        }

        if (!metrics.topProducts().isEmpty()) {
            text.append("TOP 商品：");
            int index = 1;
            for (DailyReportVo.TopProduct product : metrics.topProducts()) {
                text.append(index++).append(". ")
                        .append(product.productName()).append(' ')
                        .append(product.cupCount()).append(" 杯 ");
            }
            text.append('\n');
        }

        if (computed.hasAnomaly()) {
            text.append("异常项（由系统按阈值判定，共 ").append(computed.anomalies().size()).append(" 条）：\n");
            for (DailyReportVo.Anomaly anomaly : computed.anomalies()) {
                text.append("- ").append(anomaly.label()).append('：').append(anomaly.detail()).append('\n');
            }
        } else {
            text.append("异常项：无。请如实写「今日无异常」，不要找异常。\n");
        }
        return text.toString();
    }

    private DailyReport findByDate(LocalDate day) {
        return dailyReportMapper.selectOne(new LambdaQueryWrapper<DailyReport>()
                .eq(DailyReport::getReportDate, day)
                .last("LIMIT 1"));
    }

    private List<DailyReportVo.Anomaly> readAnomalies(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        DailyReportVo.Anomaly[] arr = readJson(json, DailyReportVo.Anomaly[].class);
        return arr == null ? new ArrayList<>() : new ArrayList<>(List.of(arr));
    }

    /** 反序列化；失败时返回 null 而不是抛错——历史日报读不出来不该让整个卡片崩掉。 */
    private <T> T readJson(String json, Class<T> type) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (Exception e) {
            log.warn("[T68] 日报 JSON 反序列化失败，按空处理：{}", e.getMessage());
            return null;
        }
    }

    private LocalDate parseDate(String date) {
        if (!StringUtils.hasText(date)) {
            return LocalDate.now(clock);
        }
        try {
            return LocalDate.parse(date.trim(), DATE_FMT);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "date 需为 yyyy-MM-dd");
        }
    }

    private static String channelLabel(String source) {
        return switch (source == null ? "" : source) {
            case "MINI_PROGRAM" -> "小程序";
            case "AI" -> "AI";
            case "COUNTER" -> "柜台";
            default -> source == null ? "未知" : source;
        };
    }
}