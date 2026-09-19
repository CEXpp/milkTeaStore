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
 * <p>字段与建表脚本一一对应：六状态、三渠道来源、支付与时间组。
 * 订单项快照在 order_item 表（T10 落地下单时写入）；本实体在 T12 中被
 * 支付编排读取，并以「条件更新」推进 PENDING_PAYMENT → PAID。</p>
 */
@Data
@TableName("orders")
public class Order {

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
