package com.milktea.order.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 稍后提醒任务（T47 下单前预期管理，表 {@code wx_subscribe_quota} 同批的 {@code remind_task}）。
 *
 * <p><b>为什么需要一张表而不是内存定时器</b>：提醒的送达时间在未来（默认 15 分钟后），
 * 期间进程可能重启；任务落库才能在重启后继续兑现——这与 4.3「超时关单」用调度而非
 * 内存延迟队列是同一个理由。</p>
 *
 * <p><b>不产生订单</b>（任务卡关键约束）：本表只描述「到点提醒某人」这一件事，
 * 不含任何商品 / 金额 / 订单字段，天然不可能被误解为一张订单；
 * 购物车内容仍留在客户端本地，提醒只是把顾客叫回来（任务卡「提醒送达后购物车内容不丢失」）。</p>
 */
@Data
@TableName("remind_task")
public class RemindTask {

    /** 待发送。 */
    public static final String STATUS_PENDING = "PENDING";

    /** 已下发（微信受理，errcode=0）。 */
    public static final String STATUS_SENT = "SENT";

    /** 已跳过（未启用订阅消息 / 无可用额度 / 下发失败）；一次性任务，跳过即终结，不无限重试。 */
    public static final String STATUS_SKIPPED = "SKIPPED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long customerId;

    /** 计划提醒时间（登记时刻 + 延迟分钟数）。 */
    private LocalDateTime remindAt;

    /** {@link #STATUS_PENDING} / {@link #STATUS_SENT} / {@link #STATUS_SKIPPED}。 */
    private String status;

    private LocalDateTime createdAt;

    /** 实际处理时间（无论成功与跳过）。 */
    private LocalDateTime handledAt;

    /** 处理结果说明（排查用）。 */
    private String note;
}
