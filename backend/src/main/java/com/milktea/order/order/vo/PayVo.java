package com.milktea.order.order.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.milktea.order.order.entity.Order;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 支付响应（LLD 3.3 {@code POST /api/customer/orders/{id}/pay}）：
 * {@code {id, status, pickupCode, paidAt, payChannel}}——顾客端支付成功后直接展示取餐码。
 */
@Data
public class PayVo implements Serializable {

    /** 订单 id。 */
    private Long id;

    /** 支付后状态：PAID。 */
    private String status;

    /** 取餐码：本单在 PAID 时分配（当日流水 001 起）。 */
    private String pickupCode;

    /** 支付时间，契约格式 yyyy-MM-dd HH:mm:ss（与 LLD 3.3 示例一致）。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime paidAt;

    /** 支付渠道：MOCK / WECHAT（预留）。 */
    private String payChannel;

    /** 由支付后的订单行组装响应。 */
    public static PayVo from(Order order) {
        PayVo vo = new PayVo();
        vo.setId(order.getId());
        vo.setStatus(order.getStatus());
        vo.setPickupCode(order.getPickupCode());
        vo.setPaidAt(order.getPaidAt());
        vo.setPayChannel(order.getPayChannel());
        return vo;
    }
}
