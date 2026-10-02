package com.milktea.order.forecast.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

/**
 * 爆单预测取数（T69，W09）。
 *
 * <p><b>预测方法：历史「同星期 × 同时段」的日均订单数。</b>
 * 之所以用这个最朴素的基线而不是更花哨的模型：单店每天的样本只有几十单，
 * 复杂模型在这么小的样本上学到的是噪声；而「上周三下午三点大概几单」这种
 * 同星期同期段的对照，是店长自己算账时就在用的方法——预测错了店长也能一眼看出原因。</p>
 *
 * <p><b>只读</b>：全部是 SELECT，不写任何表、不触发任何业务动作
 * （任务卡纪律「预测只给建议，绝不自动执行」）。</p>
 */
@Mapper
public interface DemandForecastMapper {

    /**
     * 口径：进入过「已支付及以后」的订单（含作废、不含超时关闭）——
     * 与 SRS 6.5 的「订单数」口径一致，保证预测与报表说的是同一件事。
     */
    String PAID_STATUS = " o.status IN ('PAID', 'PREPARING', 'COMPLETED', 'VOIDED') ";

    /**
     * 历史同星期同时段的日均单量。
     *
     * <p><b>关键：分母是「日历上有多少个该星期几」，而不是「有成交的有多少天」</b>。
     * 若直接 {@code SELECT DATE(paid_at) ... GROUP BY DATE(paid_at)} 再 {@code AVG}，
     * 那些「那天这个点一单没有」的日子根本不会出现在结果里，分母少了它们，
     * 均值被系统性抬高——恰在「平时清淡、偶尔爆单」的那种店上偏得最厉害。
     * 故先用递归 CTE 造出真实日历（含零成交日），再 LEFT JOIN 成交数。</p>
     *
     * <p><b>{@code AVG} 里必须再套一层 {@code COALESCE}</b>：{@code LEFT JOIN} 补出来的
     * 零成交日是 NULL，而 {@code AVG()} <b>忽略 NULL</b>——不套 COALESCE 的话分母
     * 又变回「有成交的天数」，上面那段理由等于白写。此处已实跑验证（见提交说明）。</p>
     *
     * <p>时段用 {@code TIME(paid_at)} 的闭开区间，避免边界分钟被相邻两天同时计入。</p>
     *
     * @param dayOfWeek MySQL 语义的星期（1=周日 … 7=周六）
     * @param slotStart 时段起（含）
     * @param slotEnd   时段止（不含）
     * @param since     统计起点（往前看 N 周的日期下界，含当日）
     * @param until     统计终点（含当日）
     * @return {@code {sampleDays, totalOrders, avgOrders}}
     */
    @Select("""
            WITH RECURSIVE days AS (
              SELECT DATE(?) AS d
              UNION ALL
              SELECT d + INTERVAL 7 DAY FROM days WHERE d + INTERVAL 7 DAY <= ?
            ),
            cnt AS (
              SELECT DATE(o.paid_at) AS d, COUNT(*) AS c
              FROM orders o
              WHERE o.paid_at IS NOT NULL
                AND o.paid_at >= ?
                AND o.paid_at < DATE(?) + INTERVAL 1 DAY
                AND DAYOFWEEK(o.paid_at) = ?
                AND TIME(o.paid_at) >= ?
                AND TIME(o.paid_at) < ?
                AND """ + PAID_STATUS + """
              GROUP BY DATE(o.paid_at)
            )
            SELECT COUNT(*) AS sampleDays,
                   COALESCE(SUM(COALESCE(cnt.c, 0)), 0) AS totalOrders,
                   COALESCE(AVG(COALESCE(cnt.c, 0)), 0) AS avgOrders
            FROM days LEFT JOIN cnt ON cnt.d = days.d
            """)
    Map<String, Object> selectSlotAverage(@Param("dayOfWeek") int dayOfWeek,
                                          @Param("slotStart") LocalTime slotStart,
                                          @Param("slotEnd") LocalTime slotEnd,
                                          @Param("since") LocalDate since,
                                          @Param("until") LocalDate until);

    /**
     * 兜底口径：不看星期，近 14 天同时段的日均单量。
     *
     * <p>用于历史太短时（比如刚开店，某个星期几只有 1 天样本）。单看星期几会让
     * 「上上周三恰好是雨天」这种个案变成预测值；样本不足时退回「最近这几天这个点」更稳。</p>
     *
     * <p>同样用递归 CTE 造日历，保证零成交日计入分母（理由见 {@link #selectSlotAverage}）。</p>
     */
    @Select("""
            WITH RECURSIVE days AS (
              SELECT DATE(?) AS d
              UNION ALL
              SELECT d + INTERVAL 1 DAY FROM days WHERE d + INTERVAL 1 DAY <= ?
            ),
            cnt AS (
              SELECT DATE(o.paid_at) AS d, COUNT(*) AS c
              FROM orders o
              WHERE o.paid_at IS NOT NULL
                AND o.paid_at >= ?
                AND o.paid_at < DATE(?) + INTERVAL 1 DAY
                AND TIME(o.paid_at) >= ?
                AND TIME(o.paid_at) < ?
                AND """ + PAID_STATUS + """
              GROUP BY DATE(o.paid_at)
            )
            SELECT COUNT(*) AS sampleDays,
                   COALESCE(SUM(COALESCE(cnt.c, 0)), 0) AS totalOrders,
                   COALESCE(AVG(COALESCE(cnt.c, 0)), 0) AS avgOrders
            FROM days LEFT JOIN cnt ON cnt.d = days.d
            """)
    Map<String, Object> selectRecentSlotAverage(@Param("slotStart") LocalTime slotStart,
                                                @Param("slotEnd") LocalTime slotEnd,
                                                @Param("since") LocalDate since,
                                                @Param("until") LocalDate until);
}