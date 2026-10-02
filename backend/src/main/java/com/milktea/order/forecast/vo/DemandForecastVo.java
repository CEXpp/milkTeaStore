package com.milktea.order.forecast.vo;

import java.util.List;

/**
 * 爆单预测与备料建议（T69，W09 · D3b）。
 *
 * <p><b>本 VO 只承载建议，不承载任何动作</b>（任务卡关键纪律）：没有「是否已暂停接单」
 * 这类字段可供前端顺手执行。「建议权在系统，决定权在人」在数据结构上就体现为
 * ——这里只有 {@link #level} 与 {@link #advice}，没有任何可写字段。</p>
 *
 * <p><b>不出现库存数量</b>（验收项）：{@link #advice} 只说「提前备好什么」，
 * 不说「备几份」，因为系统里根本没有库存模型（任务卡边界「不建库存模型、
 * 不产生出入库记录」）。说出数量就是编。</p>
 *
 * @param enabled        预测是否启用；false 时其余字段无意义
 * @param windowMinutes  预测窗口（分钟）
 * @param predictedOrders 预测单量（同星期同时段日均，四舍五入）
 * @param level          级别 NORMAL / BUSY / OVERLOAD
 * @param levelLabel     级别中文（正常 / 偏忙 / 可能爆单）
 * @param basis          预测依据的可读说明（让店长能判断这个数可不可信）
 * @param sampleDays     样本天数（0 表示没有历史数据，此时预测不可信）
 * @param advice         备料建议文本；无建议时为 null
 * @param degraded       AI 不可用时为 true（建议退化为模板文本）
 * @param topProducts    近期销量结构（建议的构成依据，供店长核对）
 */
public record DemandForecastVo(
        boolean enabled,
        int windowMinutes,
        int predictedOrders,
        String level,
        String levelLabel,
        String basis,
        int sampleDays,
        String advice,
        boolean degraded,
        List<TopItem> topProducts) {

    /** 级别：正常。 */
    public static final String LEVEL_NORMAL = "NORMAL";
    /** 级别：偏忙（建议提前备料）。 */
    public static final String LEVEL_BUSY = "BUSY";
    /** 级别：可能爆单（建议考虑暂停接单，但不自动执行）。 */
    public static final String LEVEL_OVERLOAD = "OVERLOAD";

    /** 预测停用时的响应（与「算了但正常」区分开）。 */
    public static DemandForecastVo disabled(int windowMinutes) {
        return new DemandForecastVo(false, windowMinutes, 0, LEVEL_NORMAL, "未开启",
                "爆单预测已在配置中关闭", 0, null, false, List.of());
    }

    /**
     * 近期销量结构的一行。
     *
     * @param productName 商品名
     * @param cupCount    近 7 日杯数
     */
    public record TopItem(String productName, long cupCount) {
    }
}