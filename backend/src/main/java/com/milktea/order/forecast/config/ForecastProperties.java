package com.milktea.order.forecast.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 爆单预测配置（T69，W09）。
 *
 * <p><b>阈值可配可关</b>（验收项）：{@code busyOrders} / {@code overloadOrders} 取 0 或负数
 * 即关闭对应提示；{@code enabled=false} 则整个预测停用、接口返回「未开启」而不是空结果
 * ——「关了」与「算了但没超阈值」对店长是两件不同的事，界面提示也不该混为一谈。</p>
 *
 * @param enabled        是否启用预测（默认启用）
 * @param windowMinutes  预测窗口（分钟），默认 15（任务卡「未来 15 分钟」）
 * @param lookbackWeeks  同星期样本窗口（周），默认 4
 * @param minSampleDays  同星期样本不足该天数时退回「最近同时段」口径
 * @param busyOrders     预测单量达到该值即提示「建议提前备料」
 * @param overloadOrders 预测单量达到该值即提示「建议考虑暂停接单」
 */
@ConfigurationProperties(prefix = "forecast")
public record ForecastProperties(
        Boolean enabled,
        Integer windowMinutes,
        Integer lookbackWeeks,
        Integer minSampleDays,
        Integer busyOrders,
        Integer overloadOrders) {

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    public int windowMinutesOrDefault() {
        return windowMinutes == null ? 15 : Math.min(Math.max(windowMinutes, 5), 60);
    }

    public int lookbackWeeksOrDefault() {
        return lookbackWeeks == null ? 4 : Math.min(Math.max(lookbackWeeks, 1), 12);
    }

    /** 同星期样本下限：低于它说明「这个星期几几乎没数据」，改用最近口径。 */
    public int minSampleDaysOrDefault() {
        return minSampleDays == null ? 2 : Math.max(minSampleDays, 1);
    }

    /** 偏忙阈值默认 5 单；≤0 表示关闭该级提示。 */
    public int busyOrdersOrZero() {
        return busyOrders == null ? 5 : busyOrders;
    }

    /** 拥挤阈值默认 8 单；≤0 表示关闭该级提示。 */
    public int overloadOrdersOrZero() {
        return overloadOrders == null ? 8 : overloadOrders;
    }
}