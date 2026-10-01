package com.milktea.order.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 队列预估内核配置（T46，LLD 11.6），绑定 {@code queue.*}。
 *
 * <p>四项均为「application.yml 给默认值、{@code shop_config} 可运行时覆盖」的运行参数；
 * 缺省或非法值在此收敛为 LLD 11.6 的基线默认（单杯 3 分钟 / 1 个制作单元 / 波动系数 0.3 /
 * 安全垫 1 分钟），保证即使配置缺失内核也不会退化为 0 分钟预估。</p>
 *
 * @param prepMinutes   单杯基准耗时（分钟，{@code t_unit}）。任务卡「单杯基准耗时（shop_config 可配）」
 * @param staff         在岗制作单元数（{@code s}，≥1）
 * @param volatility    历史波动系数（{@code k}，T69 可校准）
 * @param bufferMinutes 误差常量安全垫（分钟）
 */
@ConfigurationProperties(prefix = "queue")
public record QueueProperties(Double prepMinutes, Integer staff, Double volatility, Double bufferMinutes) {

    /** 单杯基准耗时默认值（分钟，LLD 11.6 示例 3 分钟）。 */
    public static final double DEFAULT_PREP_MINUTES = 3.0;
    /** 在岗制作单元数默认值。 */
    public static final int DEFAULT_STAFF = 1;
    /** 波动系数默认值。 */
    public static final double DEFAULT_VOLATILITY = 0.3;
    /** 误差安全垫默认值（分钟）。 */
    public static final double DEFAULT_BUFFER_MINUTES = 1.0;

    /** 缺省/非法值归一：耗时与安全垫须为正数，制作单元数须 ≥1，波动系数须非负。 */
    public QueueProperties {
        prepMinutes = (prepMinutes == null || prepMinutes <= 0) ? DEFAULT_PREP_MINUTES : prepMinutes;
        staff = (staff == null || staff < 1) ? DEFAULT_STAFF : staff;
        volatility = (volatility == null || volatility < 0) ? DEFAULT_VOLATILITY : volatility;
        bufferMinutes = (bufferMinutes == null || bufferMinutes < 0) ? DEFAULT_BUFFER_MINUTES : bufferMinutes;
    }
}
