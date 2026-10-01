package com.milktea.order.order.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 订单生命周期时间轴取数（T50，W03）。
 *
 * <p><b>零新数据</b>：六个时间戳（created_at / paid_at / started_at / completed_at / closed_at /
 * voided_at）在 V1 建表时已全部预留，本 Mapper 只做派生计算，不新增任何列或表。</p>
 *
 * <p>唯一需要「算」出来的是<b>同渠道同日制作耗时中位数</b>——用于让顾客知道自己这杯
 * 比今天同渠道的典型水平快还是慢（W03「与同渠道同日中位数对比」）。</p>
 */
@Mapper
public interface OrderTimelineMapper {

    /**
     * 同渠道、同自然日的制作耗时中位数（秒）。
     *
     * <p><b>中位数求法</b>：MySQL 8 无 {@code MEDIAN()}，用窗口函数标准写法——
     * 对耗时排序后取「第 ⌊(n+1)/2⌋ 与第 ⌈(n+1)/2⌉ 名」求平均。奇数样本两点重合即真中位数；
     * 偶数样本取中间两个的均值。已验证各 n 取值均正确（n=1→第1；n=2→1,2；n=3→2；n=4→2,3）。</p>
     *
     * <p><b>样本口径</b>：仅统计<b>已完成</b>（{@code completed_at} 非空）且 {@code paid_at}
     * 落在当日区间内的订单——未完成的单没有「制作耗时」可言，纳入会系统性低估。
     * 日期归属按 {@code paid_at}（与 SRS 6.5 当日口径一致）。</p>
     *
     * @param source   渠道（MINI_PROGRAM / AI / COUNTER）
     * @param dayStart 当日 00:00:00
     * @param dayEnd   次日 00:00:00
     * @return {@code {sampleCount, medianSeconds}}；无样本时两者为 0 / null
     */
    @Select("SELECT COUNT(*) AS sampleCount, AVG(t.dur) AS medianSeconds FROM ("
            + "SELECT TIMESTAMPDIFF(SECOND, started_at, completed_at) AS dur, "
            + "ROW_NUMBER() OVER (ORDER BY TIMESTAMPDIFF(SECOND, started_at, completed_at)) AS rn, "
            + "COUNT(*) OVER () AS cnt "
            + "FROM orders "
            + "WHERE source = #{source} AND completed_at IS NOT NULL AND started_at IS NOT NULL "
            + "AND paid_at >= #{dayStart} AND paid_at < #{dayEnd}"
            + ") t WHERE t.rn IN (FLOOR((t.cnt + 1) / 2), CEIL((t.cnt + 1) / 2))")
    Map<String, Object> selectMedianPrepSeconds(@Param("source") String source,
                                                @Param("dayStart") LocalDateTime dayStart,
                                                @Param("dayEnd") LocalDateTime dayEnd);
}
