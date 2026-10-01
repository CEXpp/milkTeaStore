package com.milktea.order.order.vo;

import java.util.List;

/**
 * 订单全生命周期时间轴（T50，W03）。
 *
 * <p><b>零新数据</b>：六个时间戳在 V1 建表时已全部预留，本 VO 只把它们渲染成可读的节点序列，
 * 并补一个「与同渠道同日的中位数对比」，让顾客知道自己这杯在今天的典型水平里算快还是慢。</p>
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param orderId           订单主键
 * @param status            当前状态（六态之一）
 * @param source            渠道（MINI_PROGRAM / AI / COUNTER），中位数即同渠道口径
 * @param nodes             时间轴节点（按时间先后；{@code done=false} 表示该节点尚未发生）
 * @param myPrepMinutes     本单制作耗时（分钟，四舍五入）；未完成 / 未开始为 {@code null}
 * @param medianPrepMinutes 同渠道同日制作耗时中位数（分钟）；无样本为 {@code null}
 * @param medianSampleCount 中位数样本量（当日同渠道已完成订单数），用于向顾客说明对比是否可靠
 */
public record OrderTimelineVo(
        Long orderId,
        String status,
        String source,
        List<Node> nodes,
        Integer myPrepMinutes,
        Integer medianPrepMinutes,
        int medianSampleCount) {

    /**
     * 单个时间轴节点。
     *
     * @param key            节点键：CREATED / PAID / PREPARING / COMPLETED / CLOSED / VOIDED
     * @param label          中文标签（已下单 / 已支付 / 开始制作 / 已出餐 / 已关闭 / 已作废）
     * @param time           该节点时间（yyyy-MM-dd HH:mm:ss）；未发生为 {@code null}
     * @param durationSeconds 距上一节点耗时（秒）；首节点或未发生为 {@code null}
     * @param done           是否已发生
     */
    public record Node(String key, String label, String time, Long durationSeconds, boolean done) {
    }
}
