package com.milktea.order.order.service;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.order.entity.OrderStatus;

import java.util.Map;

/**
 * 订单状态机硬校验（T14，LLD 4.1）：迁移表逐行硬编码，任何越界迁移抛
 * {@link BusinessException}(1004)。
 *
 * <table border="1">
 *   <caption>合法迁移（LLD 4.1 六行）</caption>
 *   <tr><th>当前状态</th><th>事件</th><th>触发方</th><th>目标状态</th><th>伴随动作</th></tr>
 *   <tr><td>PENDING_PAYMENT</td><td>支付成功</td><td>顾客 pay 接口</td><td>PAID</td>
 *       <td>分配取餐码、写 paid_at / pay_channel / transaction_id</td></tr>
 *   <tr><td>PENDING_PAYMENT</td><td>15 分钟超时</td><td>定时任务 T15</td><td>CLOSED</td><td>写 closed_at</td></tr>
 *   <tr><td>PAID</td><td>开始制作</td><td>商家 start</td><td>PREPARING</td><td>写 started_at</td></tr>
 *   <tr><td>PAID</td><td>作废</td><td>商家 void</td><td>VOIDED</td><td>写 voided_at / void_reason</td></tr>
 *   <tr><td>PREPARING</td><td>出餐完成</td><td>商家 complete</td><td>COMPLETED</td><td>写 completed_at</td></tr>
 *   <tr><td>COMPLETED / CLOSED / VOIDED</td><td>—</td><td>—</td><td>终态不可迁移</td><td>—</td></tr>
 * </table>
 *
 * <p>本类是全系统状态推进的唯一校验入口：商家三动作先经 {@link #next(OrderStatus, Event)}
 * 判定合法目标态，再以条件更新（WHERE status = 旧态）落库防并发；越界（如对已完成单点
 * 开始制作、对制作中单点作废、重复支付）统一 1004。</p>
 */
public final class OrderStateMachine {

    /**
     * 迁移事件（与 LLD 4.1「事件」列一一对应）。
     */
    public enum Event {

        /** PENDING_PAYMENT → PAID：顾客支付成功（T12）。 */
        PAY_SUCCESS("支付成功"),

        /** PENDING_PAYMENT → CLOSED：15 分钟未支付超时关闭（T15 调度）。 */
        TIMEOUT_CLOSE("支付超时"),

        /** PAID → PREPARING：商家点「开始制作」（T14）。 */
        START_PREPARING("开始制作"),

        /** PREPARING → COMPLETED：商家点「出餐」（T14）。 */
        COMPLETE("出餐完成"),

        /** PAID → VOIDED：商家作废（T14，仅未开始制作的单）。 */
        VOID("作废");

        private final String label;

        Event(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** 迁移表：当前状态 → 事件 → 目标状态；终态不出现（无迁出即拒绝）。 */
    private static final Map<OrderStatus, Map<Event, OrderStatus>> TRANSITIONS = Map.of(
            OrderStatus.PENDING_PAYMENT, Map.of(
                    Event.PAY_SUCCESS, OrderStatus.PAID,
                    Event.TIMEOUT_CLOSE, OrderStatus.CLOSED),
            OrderStatus.PAID, Map.of(
                    Event.START_PREPARING, OrderStatus.PREPARING,
                    Event.VOID, OrderStatus.VOIDED),
            OrderStatus.PREPARING, Map.of(
                    Event.COMPLETE, OrderStatus.COMPLETED)
    );

    private OrderStateMachine() {
    }

    /**
     * 校验并返回迁移目标状态；非法迁移抛 1004。
     *
     * @param from  当前状态（orders.status 原值）
     * @param event 迁移事件
     * @return 目标状态
     * @throws BusinessException 1004：当前状态不允许该事件（终态任何事件均拒绝）
     */
    public static OrderStatus next(OrderStatus from, Event event) {
        if (from == null || event == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "订单状态冲突：状态或事件缺失");
        }
        OrderStatus target = TRANSITIONS.getOrDefault(from, Map.of()).get(event);
        if (target == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(),
                    "订单状态冲突：" + from + " 状态下不允许「" + event.getLabel() + "」");
        }
        return target;
    }

    /**
     * 只读判断（不抛异常），供需要分支处理的调用方使用。
     */
    public static boolean canTransit(OrderStatus from, Event event) {
        return from != null && event != null
                && TRANSITIONS.getOrDefault(from, Map.of()).containsKey(event);
    }

    /**
     * 解析状态原值；未知状态视为冲突（防脏数据穿透状态机）。
     */
    public static OrderStatus parse(String status) {
        if (status == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "订单状态缺失");
        }
        try {
            return OrderStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "未知订单状态：" + status);
        }
    }
}
