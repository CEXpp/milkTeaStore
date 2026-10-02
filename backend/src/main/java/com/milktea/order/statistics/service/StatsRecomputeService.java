package com.milktea.order.statistics.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderItem;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.mapper.OrderItemMapper;
import com.milktea.order.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计<b>独立复算</b>器（T73，G2 · W24）。
 *
 * <p><b>为什么必须「独立」——这是本任务全部价值所在</b>：如果复算直接调用
 * {@link StatsService}（或复用 {@code StatsMapper} 的口径常量 SQL），那就不是复算，
 * 而是<b>拿报表跟它自己比</b>，无论报表算错还是算对都会「一致」。这样一张凭证只能证明
 * 「代码跑过了」，证明不了「数字是对的」。</p>
 *
 * <p>故本类<b>刻意走另一条路</b>：把当日订单逐条捞出来，在 Java 里按 SRS 6.5 的文字规则
 * 重新判一次纳入 / 排除、重新加一次钱。两条路只有在口径被正确实现时才会一致——
 * 这正是凭证能「随时证明给你看」的原因（任务卡设计理由：兑现 statistics 包已付出的
 * 「口径常量化，单点维护」工程投资）。</p>
 *
 * <p><b>6.5 的规则在这里被翻译成可读的判定</b>，每条纳入/排除都带原因：
 * 归属日按 {@code paid_at}；进入过「已支付及以后」的订单计入订单数（含作废、不含超时关闭）；
 * 营业额与杯数剔除作废；作废单实付单列为退款额。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsRecomputeService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 6.5 口径：进入过「已支付及以后」的状态集合（含作废、不含超时关闭）。 */
    private static final List<String> COUNTED_STATUSES = List.of(
            OrderStatus.PAID.name(),
            OrderStatus.PREPARING.name(),
            OrderStatus.COMPLETED.name(),
            OrderStatus.VOIDED.name());

    /** 6.5 口径：金额与杯数只认「有效已支付」（剔除作废）。 */
    private static final List<String> VALID_STATUSES = List.of(
            OrderStatus.PAID.name(),
            OrderStatus.PREPARING.name(),
            OrderStatus.COMPLETED.name());

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    /**
     * 复算某日。
     *
     * @param day 归属日
     * @return 复算结果（含纳入/排除明细与四数）
     */
    public Recomputed recompute(LocalDate day) {
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();

        // 逐条取当日「有支付时间」的订单——这是 6.5 的归属日定义（按 paid_at）
        List<Order> paidThatDay = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .ge(Order::getPaidAt, start)
                .lt(Order::getPaidAt, end)
                .orderByAsc(Order::getPaidAt, Order::getId));

        // 当日创建但没有 paid_at 的订单：超时关闭 / 待支付，按 6.5 不计入任何数字，
        // 但必须出现在「排除明细」里——「为什么今天有 3 单没进账」是店长最常问的问题
        List<Order> createdThatDayWithoutPaid = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .ge(Order::getCreatedAt, start)
                .lt(Order::getCreatedAt, end)
                .isNull(Order::getPaidAt)
                .orderByAsc(Order::getCreatedAt, Order::getId));

        Map<Long, Long> cupsByOrderId = cupsByOrderId(paidThatDay);

        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal refund = BigDecimal.ZERO;
        long orderCount = 0;
        long cupCount = 0;
        List<OrderRow> included = new ArrayList<>();
        List<OrderRow> excluded = new ArrayList<>();

        for (Order order : paidThatDay) {
            String status = order.getStatus();
            if (!COUNTED_STATUSES.contains(status)) {
                excluded.add(row(order, "状态「" + status + "」不在 6.5 的订单数口径内"));
                continue;
            }
            orderCount++;
            BigDecimal amount = order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount();
            boolean voided = OrderStatus.VOIDED.name().equals(status);

            if (voided) {
                // 作废：已退款，钱与货都不该计入成交量，单列为退款额
                refund = refund.add(amount);
                included.add(row(order, "作废单：计入订单数，实付单列为退款额，不计营业额与杯数"));
            } else {
                revenue = revenue.add(amount);
                long cups = cupsByOrderId.getOrDefault(order.getId(), 0L);
                cupCount += cups;
                included.add(row(order, "有效已支付：计入营业额与 " + cups + " 杯"));
            }
        }

        for (Order order : createdThatDayWithoutPaid) {
            String reason = OrderStatus.CLOSED.name().equals(order.getStatus())
                    ? "超时关闭单：无支付时间，按 6.5 不计入营业额与订单数"
                    : "待支付单：尚未支付，按 6.5 不计入营业额与订单数";
            excluded.add(row(order, reason));
        }

        return new Recomputed(
                day.format(DATE_FMT),
                revenue,
                orderCount,
                cupCount,
                refund,
                included,
                excluded);
    }

    /** 批量取这些订单的主饮品件数（6.5：加料与规格不另计，故只累加 quantity）。 */
    private Map<Long, Long> cupsByOrderId(List<Order> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        Map<Long, Long> result = new LinkedHashMap<>();
        for (OrderItem item : orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .in(OrderItem::getOrderId, orderIds))) {
            result.merge(item.getOrderId(), item.getQuantity() == null ? 0L : item.getQuantity().longValue(),
                    Long::sum);
        }
        return result;
    }

    private OrderRow row(Order order, String reason) {
        return new OrderRow(
                order.getOrderNo(),
                order.getStatus(),
                order.getSource(),
                order.getPickupCode(),
                order.getTotalAmount() == null ? null : order.getTotalAmount().toPlainString(),
                order.getPaidAt() == null ? null : order.getPaidAt().format(DATETIME_FMT),
                StringUtils.hasText(order.getVoidReason()) ? order.getVoidReason() : null,
                reason);
    }

    /**
     * 一行订单明细。
     *
     * @param orderNo  订单号
     * @param status   状态
     * @param source   渠道
     * @param pickupCode 取餐码（可能为 null）
     * @param amount   实付金额
     * @param paidAt   支付时间
     * @param voidReason 作废原因
     * @param reason   本次纳入 / 排除的判定理由
     */
    public record OrderRow(String orderNo, String status, String source, String pickupCode,
                           String amount, String paidAt, String voidReason, String reason) {
    }

    /**
     * 复算结果。
     *
     * @param date       归属日
     * @param revenue    复算营业额
     * @param orderCount 复算订单数
     * @param cupCount   复算杯数
     * @param refund     复算退款额
     * @param included   纳入明细（逐条带判定理由）
     * @param excluded   排除明细（逐条带判定理由）
     */
    public record Recomputed(String date, BigDecimal revenue, long orderCount, long cupCount,
                             BigDecimal refund, List<OrderRow> included, List<OrderRow> excluded) {
    }
}