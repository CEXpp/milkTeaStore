package com.milktea.order.queue.vo;

/**
 * 动态接单节奏（T48，W18）。
 *
 * <p><b>只读建议，不落任何写操作</b>：本 VO 只描述「此刻队列压力如何、建议怎么做」，
 * 不含任何「已暂停 / 已拒绝」的执行结果——暂停与否由店长在开关键上决定（任务卡：
 * 「任何情况下不自动暂停接单」）。也正因为系统不产生拒绝记录，
 * 统计口径（6.5）不会被本功能改变（验收项「统计口径与手工核对一致」）。</p>
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param level            压力等级：{@code NORMAL} / {@code BUSY} / {@code OVERLOAD}
 * @param totalCups        当前队列工作量（杯，待制作 + 制作中）
 * @param etaMinutes       此刻新下单的预估等待（分钟）
 * @param etaLow           误差区间下限（分钟）
 * @param etaHigh          误差区间上限（分钟）
 * @param busyCups         生效的偏忙阈值（杯），便于商家端解释判定依据
 * @param overloadCups     生效的拥挤阈值（杯）
 * @param suggestPause     是否建议暂停接单（仅 {@code OVERLOAD} 为 true；仍是建议，非执行指令）
 * @param suggestion       给店长的中文建议文案
 * @param computedAt       计算时间戳（与队列快照同源）
 */
public record QueuePaceVo(
        String level,
        long totalCups,
        int etaMinutes,
        int etaLow,
        int etaHigh,
        int busyCups,
        int overloadCups,
        boolean suggestPause,
        String suggestion,
        String computedAt) {

    /** 等级：正常。 */
    public static final String LEVEL_NORMAL = "NORMAL";

    /** 等级：偏忙。 */
    public static final String LEVEL_BUSY = "BUSY";

    /** 等级：拥挤。 */
    public static final String LEVEL_OVERLOAD = "OVERLOAD";
}
