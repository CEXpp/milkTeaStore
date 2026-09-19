package com.milktea.order.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单（orders 表）。
 *
 * <p>状态取值见 LLD 4.1 状态机，渠道见 LLD 2.2。本任务（T10）只产出
 * {@link #STATUS_PENDING_PAYMENT} 状态的小程序单；状态迁移硬校验由 T14 承担。</p>
 */
@Data
@TableName("orders")
public class Order {

    /** 待支付：下单初始状态（LLD 4.1） */
    public static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";

    /** 来源渠道：小程序（LLD 2.2，另见 AI / COUNTER） */
    public static final String SOURCE_MINI_PROGRAM = "MINI_PROGRAM";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号：yyMMdd + 5 位序列（LLD 4.2） */
    private String orderNo;

    /** MINI_PROGRAM / AI / COUNTER */
    private String source;

    /** 见 LLD 4.1 */
    private String status;

    /** 柜台单为 null */
    private Long customerId;

    /** MOCK / WECHAT（预留） */
    private String payChannel;

    /** 支付流水号（Mock 生成 / 微信预留） */
    private String transactionId;

    /** 应付总额（下单瞬间的快照） */
    private BigDecimal totalAmount;

    /** 取餐码（进入 PAID 时分配，T12） */
    private String pickupCode;

    /** 口味备注 */
    private String remark;

    private LocalDateTime paidAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime closedAt;

    private LocalDateTime voidedAt;

    private String voidReason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
