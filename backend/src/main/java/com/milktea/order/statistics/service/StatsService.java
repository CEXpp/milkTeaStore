package com.milktea.order.statistics.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.result.PageResult;
import com.milktea.order.common.util.MoneyUtils;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.statistics.mapper.StatsMapper;
import com.milktea.order.statistics.vo.StatsChannelVo;
import com.milktea.order.statistics.vo.StatsOrderRowVo;
import com.milktea.order.statistics.vo.StatsRankingVo;
import com.milktea.order.statistics.vo.StatsSummaryVo;
import com.milktea.order.statistics.vo.StatsTrendVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 账台统计服务（T34，LLD 3.5.4 / SRS 6.5）。
 *
 * <p><b>口径全部下沉到 {@link StatsMapper} 的常量 SQL 片段</b>（statistics 包约定
 * 「口径常量化，单点维护」），本类只负责：解析入参 → 按 {@code app.time-zone} 切自然日 →
 * 调用聚合 → 把 JDBC 原始值格式化为契约要求的类型（金额两位小数字符串、时间 yyyy-MM-dd HH:mm:ss）。</p>
 *
 * <p><b>不加缓存</b>（T34 卡）：单店量级下每次直接聚合，订单状态一变统计立刻跟得上。</p>
 */
@Service
public class StatsService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int DEFAULT_TREND_DAYS = 7;
    private static final int MAX_TREND_DAYS = 31;
    private static final int DEFAULT_TOP = 10;
    private static final int MAX_TOP = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /** 排行区间取值（LLD 3.5.4 {@code range=today|7d}） */
    private static final String RANGE_TODAY = "today";
    private static final String RANGE_7D = "7d";

    private final StatsMapper statsMapper;
    private final OrderMapper orderMapper;

    /** 自然日基准：与 T11 流水号服务同源（{@code app.time-zone}，默认东八区）。 */
    private final Clock clock;

    public StatsService(StatsMapper statsMapper,
                        OrderMapper orderMapper,
                        @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.statsMapper = statsMapper;
        this.orderMapper = orderMapper;
        this.clock = Clock.system(ZoneId.of(timeZone));
    }

    /**
     * 当日概览（LLD 3.5.4 {@code GET /api/admin/stats/summary?date=}）。
     *
     * @param date 归属日 yyyy-MM-dd，空则取服务端「今天」
     * @return 概览（营业额 / 订单数 / 杯数 / 退款额 / 渠道分布）
     */
    public StatsSummaryVo summary(String date) {
        LocalDate day = parseDate(date);
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();

        Map<String, Object> totals = statsMapper.selectDayTotals(start, end);
        Map<String, Object> cups = statsMapper.selectDayCupCount(start, end);
        List<StatsChannelVo> channels = new ArrayList<>();
        for (Map<String, Object> row : statsMapper.selectDayChannels(start, end)) {
            channels.add(new StatsChannelVo(text(row.get("source")),
                    asLong(row.get("orderCount")), money(row.get("amount"))));
        }
        return new StatsSummaryVo(day.format(DATE_FMT),
                money(totals.get("totalAmount")),
                asLong(totals.get("orderCount")),
                asLong(cups.get("cupCount")),
                money(totals.get("refundAmount")),
                channels);
    }

    /**
     * 近 N 日趋势（LLD 3.5.4 {@code GET /api/admin/stats/trend?days=7}）。
     *
     * @param days 天数（从今天往前数），空则 7，取值区间 1..31
     * @return 逐日趋势，按日期升序；无单日补 0（图表日期轴连续）
     */
    public List<StatsTrendVo> trend(Integer days) {
        int span = clamp(days == null ? DEFAULT_TREND_DAYS : days, 1, MAX_TREND_DAYS);
        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.minusDays(span - 1L);

        Map<String, Map<String, Object>> byDay = new HashMap<>();
        for (Map<String, Object> row : statsMapper.selectDailyTrend(from.atStartOfDay(),
                today.plusDays(1).atStartOfDay())) {
            byDay.put(text(row.get("day")), row);
        }
        List<StatsTrendVo> result = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            String key = from.plusDays(i).format(DATE_FMT);
            Map<String, Object> row = byDay.get(key);
            result.add(new StatsTrendVo(key,
                    row == null ? 0L : asLong(row.get("orderCount")),
                    row == null ? MoneyUtils.format(null) : money(row.get("amount"))));
        }
        return result;
    }

    /**
     * 商品销量排行（LLD 3.5.4 {@code GET /api/admin/stats/ranking?range=today|7d&top=10}）。
     *
     * @param range {@code today}（今天）或 {@code 7d}（近 7 日含今天），空则 today
     * @param top   取前 N 名，空则 10，取值区间 1..50
     * @return 排行行，按件数降序
     * @throws BusinessException range 非法时 1001
     */
    public List<StatsRankingVo> ranking(String range, Integer top) {
        String normalized = StringUtils.hasText(range) ? range.trim().toLowerCase() : RANGE_TODAY;
        if (!RANGE_TODAY.equals(normalized) && !RANGE_7D.equals(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "range 仅支持 today 或 7d");
        }
        int limit = clamp(top == null ? DEFAULT_TOP : top, 1, MAX_TOP);
        LocalDate today = LocalDate.now(clock);
        LocalDate from = RANGE_7D.equals(normalized) ? today.minusDays(6) : today;

        List<StatsRankingVo> result = new ArrayList<>();
        for (Map<String, Object> row : statsMapper.selectProductRanking(from.atStartOfDay(),
                today.plusDays(1).atStartOfDay(), limit)) {
            result.add(new StatsRankingVo(asLongOrNull(row.get("productId")),
                    text(row.get("productName")), asLong(row.get("cupCount")), money(row.get("amount"))));
        }
        return result;
    }

    /**
     * 订单流水明细（LLD 3.5.4 {@code GET /api/admin/stats/orders?date=&status=&page=&size=}）。
     *
     * <p><b>归属日按 {@code created_at}（下单时间）</b>：这份明细是给商家逐笔对账的「当日流水」，
     * 需要把超时关闭、待支付这类<b>没有 {@code paid_at} 的异常单</b>也列出来（T34 卡「含异常单」）；
     * 而概览的营业额 / 订单数按 SRS 6.5 以 {@code paid_at} 归属当日。两者口径不同是刻意的——
     * 概览回答「今天赚了多少」，明细回答「今天发生过哪些单」；明细行里同时给出 {@code createdAt}
     * 与 {@code paidAt}，便于交叉核对。</p>
     *
     * @param date   归属日 yyyy-MM-dd，空则今天
     * @param status 状态过滤，空则全部；非法状态抛 1001
     * @param page   页码，从 1 起
     * @param size   每页条数，默认 20，上限 100
     * @return 分页流水明细，按下单时间倒序
     */
    public PageResult<StatsOrderRowVo> orders(String date, String status, int page, int size) {
        LocalDate day = parseDate(date);
        String normalizedStatus = normalizeStatus(status);
        int safePage = Math.max(page, 1);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        long total = orderMapper.selectCount(orderQuery(day, normalizedStatus));
        List<Order> orders = total == 0 ? List.of()
                : orderMapper.selectList(orderQuery(day, normalizedStatus)
                        .last("LIMIT " + (long) (safePage - 1) * safeSize + "," + safeSize));
        return PageResult.of(orders.stream().map(StatsService::toRow).toList(), total, safePage, safeSize);
    }

    /** 每次新建 wrapper（MyBatis-Plus 的条件对象在生成 SQL 时会被消费，复用同一实例不安全）。 */
    private LambdaQueryWrapper<Order> orderQuery(LocalDate day, String status) {
        return new LambdaQueryWrapper<Order>()
                .ge(Order::getCreatedAt, day.atStartOfDay())
                .lt(Order::getCreatedAt, day.plusDays(1).atStartOfDay())
                .eq(StringUtils.hasText(status), Order::getStatus, status)
                .orderByDesc(Order::getCreatedAt, Order::getId);
    }

    private static StatsOrderRowVo toRow(Order order) {
        return new StatsOrderRowVo(order.getId(), order.getOrderNo(), order.getStatus(), order.getSource(),
                order.getPickupCode(), MoneyUtils.format(order.getTotalAmount()),
                format(order.getCreatedAt()), format(order.getPaidAt()), format(order.getStartedAt()),
                format(order.getCompletedAt()), format(order.getClosedAt()), format(order.getVoidedAt()),
                order.getVoidReason());
    }

    /** 状态过滤校验：取值必须落在 {@link OrderStatus} 内，避免拼错状态静默返回空列表。 */
    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        for (OrderStatus candidate : OrderStatus.values()) {
            if (candidate.name().equals(normalized)) {
                return normalized;
            }
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "status 非法：" + status);
    }

    /** 空则取服务端「今天」（按 {@code app.time-zone}）；格式非法抛 1001。 */
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

    private static String format(LocalDateTime time) {
        return time == null ? null : time.format(DATETIME_FMT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    /** JDBC 聚合值的容错取值：COUNT 出 Long，SUM(DECIMAL) 出 BigDecimal，SUM(INT) 出 BigDecimal。 */
    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(value.toString());
    }

    private static Long asLongOrNull(Object value) {
        return value == null ? null : asLong(value);
    }

    /** 金额统一走 {@link MoneyUtils}，保证对外永远是两位小数字符串。 */
    private static String money(Object value) {
        if (value instanceof BigDecimal decimal) {
            return MoneyUtils.format(decimal);
        }
        return value == null ? MoneyUtils.format(null) : MoneyUtils.format(new BigDecimal(value.toString()));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
