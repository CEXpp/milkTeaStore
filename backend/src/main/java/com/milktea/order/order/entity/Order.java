package com.milktea.order.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单主表（orders，LLD 2.3 数据模型 / V1__init_schema.sql）。
 *
 * <p>字段与建表脚本一一对应：六状态（取值见 {@link OrderStatus}）、三渠道来源（{@link #SOURCE_MINI_PROGRAM} 等）、
 * 支付与时间组。下单（T10）写入 PENDING_PAYMENT 订单与 order_item 规格快照；支付（T12）
 * 以「条件更新」推进 PENDING_PAYMENT → PAID，并在同一事务内写入支付三字段与取餐码。</p>
 */
@Data
@TableName("orders")
public class Order {

    /** 来源渠道：小程序（LLD 2.2，另见 AI / COUNTER）。 */
    public static final String SOURCE_MINI_PROGRAM = "MINI_PROGRAM";

    /** 来源渠道：柜台人工点单（T14/T20，创建即 PAID）。 */
    public static final String SOURCE_COUNTER = "COUNTER";

    /** 来源渠道：AI 点单（T32 草稿转订单）。 */
    public static final String SOURCE_AI = "AI";

    /** 支付渠道：柜台当面收款（不走 PaymentProvider，创建即 PAID 时写入）。 */
    public static final String PAY_CHANNEL_COUNTER = "COUNTER";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号：yyMMdd + 5 位序列（T11 SequenceService 发放）。 */
    private String orderNo;

    /** 下单来源：MINI_PROGRAM / AI / COUNTER。 */
    private String source;

    /** 状态取值见 {@link OrderStatus}。 */
    private String status;

    /** 顾客 id；柜台单为 NULL。 */
    private Long customerId;

    /** 支付渠道：MOCK / WECHAT（预留），进入 PAID 时写入。 */
    private String payChannel;

    /** 渠道交易号，进入 PAID 时写入。 */
    private String transactionId;

    /** 应付总额（快照）。 */
    private BigDecimal totalAmount;

    /** 取餐码：进入 PAID 时分配（T11 SequenceService）。 */
    private String pickupCode;

    /** 口味备注。 */
    private String remark;

    /**
     * 结构化备注标签（V3 加列 {@code orders.remark_tags}，JSON 数组）。
     *
     * <p>由 T53（备注语义结构化）写入；在那之前该列恒为 {@code null}。T58 出餐核对清单只读它的
     * <b>条数</b>用于提示「本单有 N 项特殊要求」，不解析具体语义——避免越界替 T53 做理解。</p>
     */
    private String remarkTags;

    /** 支付时间（进入 PAID）。 */
    private LocalDateTime paidAt;

    /** 开始制作时间。 */
    private LocalDateTime startedAt;

    /** 出餐时间。 */
    private LocalDateTime completedAt;

    /** 超时关闭时间。 */
    private LocalDateTime closedAt;

    /** 作废时间。 */
    private LocalDateTime voidedAt;

    /** 作废原因（商家填写）。 */
    private String voidReason;

    /**
     * 顾客申报「我到店还需 X 分钟」（W02「我将到」，V3 加列）。
     *
     * <p>取值 3 / 5 / 10（分钟），或 {@code null} 表示未申报 / 已撤销。T51 的看板据此给出
     * <b>建议制作顺序</b>（到达近的优先），未申报的一律按 {@code paid_at} 排——与 LLD 3.5
     * 「先付先做」完全一致，因此不申报时看板顺序与既往行为逐字相同。</p>
     */
    private Integer etaMinutes;

    /**
     * 顾客申报「我已到店」的时间（T49 到店握手，V6 加列）。
     *
     * <p>与 {@link #etaMinutes}（W02「我将到」）构成双向到店信号体系：前者表示「已在店」，
     * 后者表示「预计何时到」。两者都只是<b>信号</b>——不参与状态机、不参与超时关单、不参与统计。</p>
     *
     * <p>但两者的排序待遇<b>不同</b>（各有任务卡依据）：{@code etaMinutes} <b>参与</b>看板建议排序
     * （T51 验收「申报后看板顺序变化」），而 {@code arrivedAt} <b>不参与</b>排序、只打一个标识
     * （T49 验收「不强制改排序」）。</p>
     */
    private LocalDateTime arrivedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
