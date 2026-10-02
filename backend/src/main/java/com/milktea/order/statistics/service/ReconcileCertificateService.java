package com.milktea.order.statistics.service;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.util.MoneyUtils;
import com.milktea.order.statistics.vo.ReconcileCertificateVo;
import com.milktea.order.statistics.vo.StatsSummaryVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 对账凭证服务（T73，G2 · W24）。
 *
 * <p>把 {@link StatsRecomputeService} 的独立复算结果与 {@link StatsService} 的报表数
 * 逐项比对，产出一张可读、可导出的凭证。</p>
 *
 * <p><b>「差异可定位到具体订单」</b>（验收项）：不一致时，逐项结论里会点名是哪个数差、
 * 差多少，并把当日全部纳入/排除明细一并给出——店长据此能直接翻到那一单，
 * 而不必自己再查一遍。</p>
 */
@Slf4j
@Service
public class ReconcileCertificateService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StatsService statsService;
    private final StatsRecomputeService recomputeService;

    private final Clock clock;

    public ReconcileCertificateService(StatsService statsService,
                                       StatsRecomputeService recomputeService,
                                       @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.statsService = statsService;
        this.recomputeService = recomputeService;
        this.clock = Clock.system(ZoneId.of(timeZone));
    }

    /**
     * 生成某日对账凭证。
     *
     * @param date 归属日；空则取服务端「今天」
     * @throws BusinessException 1001 日期格式非法
     */
    public ReconcileCertificateVo certificate(String date) {
        LocalDate day = parseDate(date);
        String key = day.format(DATE_FMT);

        StatsSummaryVo reported = statsService.summary(key);
        StatsRecomputeService.Recomputed recomputed = recomputeService.recompute(day);

        List<ReconcileCertificateVo.Item> items = new ArrayList<>();
        items.add(compare("营业额", reported.totalAmount(), MoneyUtils.format(recomputed.revenue())));
        items.add(compare("订单数", String.valueOf(reported.orderCount()), String.valueOf(recomputed.orderCount())));
        items.add(compare("杯数", String.valueOf(reported.cupCount()), String.valueOf(recomputed.cupCount())));
        items.add(compare("退款额", reported.refundAmount(), MoneyUtils.format(recomputed.refund())));

        boolean consistent = items.stream().allMatch(ReconcileCertificateVo.Item::match);
        String conclusion = consistent
                ? "一致：报表四个数与独立复算逐项相同，当日口径自洽。"
                : "不一致：" + describeMismatch(items) + "。请按下表当日明细逐单核对。";

        log.info("[T73] reconcile date={} consistent={} included={} excluded={}",
                day, consistent, recomputed.included().size(), recomputed.excluded().size());

        return new ReconcileCertificateVo(
                key,
                caliber(),
                items,
                consistent,
                conclusion,
                toRows(recomputed.included()),
                toRows(recomputed.excluded()),
                LocalDate.now(clock).format(DATE_FMT) + " " + java.time.LocalTime.now(clock).format(DATETIME_FMT));
    }

    /**
     * 导出为纯文本（验收项「可导出」）。
     *
     * <p>选纯文本而非 CSV/Excel：凭证的价值是「一眼读完并留下痕迹」，CSV 打开就是一堆
     * 单元格，反而更难读；而且 CSV 里的订单号更容易被顺手转发。需要归档时整段复制即可。</p>
     *
     * <p><b>不含任何个人信息</b>：导出内容与 {@link ReconcileCertificateVo} 完全一致，
     * 没有顾客 id / openid / 昵称 / 手机号（VO 层就不含，故这里无从掺入）。</p>
     */
    public String export(String date) {
        ReconcileCertificateVo vo = certificate(date);
        StringBuilder text = new StringBuilder();
        text.append("对账凭证  ").append(vo.date()).append('\n');
        text.append("生成时间：").append(vo.generatedAt()).append('\n');
        text.append("结论：").append(vo.conclusion()).append("\n\n");

        text.append("【统计口径】\n");
        for (String line : vo.caliber()) {
            text.append("  · ").append(line).append('\n');
        }

        text.append("\n【逐项比对】\n");
        text.append(String.format("  %-8s %14s %14s %6s%n", "指标", "报表数", "独立复算数", "结果"));
        for (ReconcileCertificateVo.Item item : vo.items()) {
            text.append(String.format("  %-8s %14s %14s %6s%n",
                    item.name(), item.reported(), item.recomputed(), item.match() ? "一致" : "不一致"));
        }

        text.append("\n【纳入明细】共 ").append(vo.included().size()).append(" 单\n");
        for (ReconcileCertificateVo.Row row : vo.included()) {
            text.append("  ").append(row.orderNo())
                    .append(' ').append(row.status())
                    .append(" 金额 ").append(row.amount())
                    .append(" — ").append(row.reason()).append('\n');
        }

        text.append("\n【排除明细】共 ").append(vo.excluded().size()).append(" 单\n");
        for (ReconcileCertificateVo.Row row : vo.excluded()) {
            text.append("  ").append(row.orderNo())
                    .append(' ').append(row.status())
                    .append(" 金额 ").append(row.amount())
                    .append(" — ").append(row.reason()).append('\n');
        }
        return text.toString();
    }

    /** 口径说明：把 6.5 的规则逐条写出来，让凭证自己解释自己是怎么算的。 */
    private List<String> caliber() {
        return List.of(
                "归属日：按支付完成时间（paid_at）归属当日；超时关闭单没有支付时间，不落入任何一天。",
                "订单数：当日进入过「已支付及以后」状态的订单数（含作废，不含超时关闭）。",
                "营业额：有效已支付（剔除作废）的实付合计。",
                "杯数：主饮品件数合计；加料与规格是规格项，不产生额外件数。",
                "退款额：作废单的实付合计，单列扣减，不与营业额混算。",
                "复算方式：逐单取当日订单，在应用层按上述规则重判一次，与报表 SQL 聚合相互独立。");
    }

    private ReconcileCertificateVo.Item compare(String name, String reported, String recomputed) {
        String left = StringUtils.hasText(reported) ? reported : "0";
        String right = StringUtils.hasText(recomputed) ? recomputed : "0";
        // 金额按数值比（避免 "18.5" 与 "18.50" 被判成不一致），非金额按字面比
        boolean match = name.equals("营业额") || name.equals("退款额")
                ? numericEquals(left, right)
                : left.equals(right);
        return new ReconcileCertificateVo.Item(name, left, right, match);
    }

    /** 金额用 BigDecimal 比较（"18.5".compareTo("18.50") != 0，但它们是同一个数）。 */
    private boolean numericEquals(String left, String right) {
        try {
            return new java.math.BigDecimal(left).compareTo(new java.math.BigDecimal(right)) == 0;
        } catch (NumberFormatException e) {
            return left.equals(right);
        }
    }

    private String describeMismatch(List<ReconcileCertificateVo.Item> items) {
        List<String> diff = new ArrayList<>();
        for (ReconcileCertificateVo.Item item : items) {
            if (!item.match()) {
                diff.add(item.name() + " 报表 " + item.reported() + " vs 复算 " + item.recomputed());
            }
        }
        return String.join("；", diff);
    }

    private List<ReconcileCertificateVo.Row> toRows(List<StatsRecomputeService.OrderRow> rows) {
        List<ReconcileCertificateVo.Row> list = new ArrayList<>(rows.size());
        for (StatsRecomputeService.OrderRow row : rows) {
            list.add(new ReconcileCertificateVo.Row(
                    row.orderNo(), row.status(), row.source(), row.amount(), row.voidReason(), row.reason()));
        }
        return list;
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
}