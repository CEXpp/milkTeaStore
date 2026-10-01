package com.milktea.order.queue.vo;

/**
 * 顾客端队列预估（T46，LLD 11.6 的 {@code etaMinutes / etaLow / etaHigh / position} 契约）。
 *
 * <p>两个使用场景共用同一形态：</p>
 * <ul>
 *   <li><b>指定订单</b>（进行中订单页，T49）：{@code position} = 该单在队列中的序号（1 起），
 *       {@code queueCups} = 排在该单之前的队列工作量（杯）；</li>
 *   <li><b>未指定订单</b>（结算页下单前预估，T47）：{@code orderId} / {@code position} 为 {@code null}，
 *       {@code queueCups} = 当前全店队列工作量（杯）= 此刻加入队列的前序工作量。</li>
 * </ul>
 *
 * <p>{@code position = 0} 表示订单存在但不在队列中（待支付 / 已完成 / 超时关闭 / 已作废），
 * 此时预估无队列含义，前端对非进行中订单不展示（T49 只在进行中订单页展示预估）。</p>
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param orderId    订单主键；{@code null} 表示未指定订单（下单前预估）
 * @param position   队列序号（1 起）；{@code null} 表示未指定订单，{@code 0} 表示不在队列中
 * @param queueCups  前序队列工作量（杯）
 * @param etaMinutes 预估等待（分钟）
 * @param etaLow     误差区间下限（分钟，非负）
 * @param etaHigh    误差区间上限（分钟）
 * @param computedAt 计算时间戳（yyyy-MM-dd HH:mm:ss，SRS 7 队列预估实体字段）
 */
public record QueueEstimateVo(
        Long orderId,
        Long position,
        long queueCups,
        int etaMinutes,
        int etaLow,
        int etaHigh,
        String computedAt) {
}
