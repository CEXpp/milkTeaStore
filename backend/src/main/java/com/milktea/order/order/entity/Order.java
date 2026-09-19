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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
