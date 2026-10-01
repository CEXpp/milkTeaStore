package com.milktea.order.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 动态接单节奏阈值（T48），绑定 {@code queue.pace.*}。
 *
 * <p><b>为什么是「阈值 + 建议」而不是「阈值 + 自动执行」</b>：任务卡明确
 * 「超最高阈值时仅『建议』店长暂停，不自动执行（保留 4.6 手动开关语义）」，
 * 理由是「用顾客自主判断替代系统强制拒绝：体验更好、店长不必反复拨开关、
 * 不新增拒绝记录因而不得污染 6.5 口径」。因此本配置只决定<b>何时提示</b>，
 * 暂停动作永远由人触发（见 admin-web 看板的确认弹窗）。</p>
 *
 * @param busyCups     偏忙阈值（队列杯数达到即提示，但仍正常接单）
 * @param overloadCups 拥挤阈值（达到即「建议暂停」；注意是建议，系统不会自行暂停）
 */
@ConfigurationProperties(prefix = "queue.pace")
public record QueuePaceProperties(Integer busyCups, Integer overloadCups) {

    /** 偏忙阈值默认（杯）。 */
    public static final int DEFAULT_BUSY_CUPS = 10;

    /** 拥挤阈值默认（杯）。 */
    public static final int DEFAULT_OVERLOAD_CUPS = 20;

    /** 归一：两者均须为正整数，且拥挤阈值必须严格大于偏忙阈值（否则提示区间退化为空）。 */
    public QueuePaceProperties {
        busyCups = (busyCups == null || busyCups < 1) ? DEFAULT_BUSY_CUPS : busyCups;
        overloadCups = (overloadCups == null || overloadCups <= busyCups)
                ? Math.max(busyCups * 2, busyCups + 1)
                : overloadCups;
    }
}
