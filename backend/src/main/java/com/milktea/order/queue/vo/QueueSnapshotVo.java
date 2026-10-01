package com.milktea.order.queue.vo;

/**
 * 全店队列快照（T46，LLD 11.6）。
 *
 * <p>语义等价于「此刻新来一单的预估」：队列工作量取全店待制作 + 制作中的主饮品件数。
 * 供 T48（动态接单节奏：按队列压力建议暂停 / 恢复）与 T69（爆单预测）取数；
 * 暴露生效参数（{@code unitMinutes} / {@code staff}）便于商家端解释预估来源。</p>
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param waitingOrders   待制作订单数（{@code PAID}）
 * @param preparingOrders 制作中订单数（{@code PREPARING}）
 * @param waitingCups     待制作主饮品件数（杯）
 * @param preparingCups   制作中主饮品件数（杯）
 * @param totalCups       队列工作量（杯）= waitingCups + preparingCups
 * @param etaMinutes      预估等待（分钟）
 * @param etaLow          误差区间下限（分钟，非负）
 * @param etaHigh         误差区间上限（分钟）
 * @param unitMinutes     生效的单杯基准耗时（分钟）
 * @param staff           生效的在岗制作单元数
 * @param computedAt      计算时间戳（yyyy-MM-dd HH:mm:ss，SRS 7 队列预估实体字段）
 */
public record QueueSnapshotVo(
        long waitingOrders,
        long preparingOrders,
        long waitingCups,
        long preparingCups,
        long totalCups,
        int etaMinutes,
        int etaLow,
        int etaHigh,
        double unitMinutes,
        int staff,
        String computedAt) {
}
