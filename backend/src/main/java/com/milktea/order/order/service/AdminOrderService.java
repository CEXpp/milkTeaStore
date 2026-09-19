package com.milktea.order.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.util.MoneyUtils;
import com.milktea.order.order.dto.OptionSnapshot;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderItem;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.mapper.OrderItemMapper;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.order.vo.AdminOrderSummaryVo;
import com.milktea.order.order.vo.BoardPendingCardVo;
import com.milktea.order.order.vo.BoardPreparingCardVo;
import com.milktea.order.order.vo.BoardTodayVo;
import com.milktea.order.order.vo.BoardVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商家订单服务（T14）：看板查询 + 状态机三动作（start / complete / void）。
 *
 * <p><b>状态机硬校验</b>（LLD 4.1）：三个动作先经 {@link OrderStateMachine#next} 判定，
 * 再以「条件更新」落库（WHERE status = 旧态）——并发双击只有一次能成功，另一次 1004；
 * 越界迁移（对已完成单点开始制作、对制作中单点作废等）统一 1004。</p>
 *
 * <p><b>看板</b>（LLD 3.5）：pending = PAID 按支付时间正序（先付先做）、preparing = PREPARING
 * 按开始时间正序；today 四数口径对齐 SRS 6.5——订单数 / 营业额（扣除当日作废退款额）/
 * 杯数（主饮品件数，即 order_item.quantity 合计）/ 退款额。单店量级直接内存聚合，与
 * T34 统计接口同口径。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrderService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 当日进入过「已支付」及以后状态的状态集合（SRS 6.5：含作废，不含超时关闭）。 */
    private static final List<String> TODAY_PAID_STATUSES = List.of(
            OrderStatus.PAID.name(),
            OrderStatus.PREPARING.name(),
            OrderStatus.COMPLETED.name(),
            OrderStatus.VOIDED.name());

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    /**
     * 看板全量查询（3 秒轮询）：双分区卡片 + 今日概览四数。
     */
    public BoardVo board() {
        LocalDateTime now = LocalDateTime.now();

        List<Order> pendingOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderStatus.PAID.name())
                .orderByAsc(Order::getPaidAt)
                .orderByAsc(Order::getId));
        List<Order> preparingOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderStatus.PREPARING.name())
                .orderByAsc(Order::getStartedAt)
                .orderByAsc(Order::getId));

        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        // 今日已支付及以后（含作废）：营业额/订单数/杯数基数
        List<Order> todayOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .in(Order::getStatus, TODAY_PAID_STATUSES)
                .ge(Order::getPaidAt, dayStart)
                .lt(Order::getPaidAt, dayEnd));
        // 今日作废单（按作废时间归属）：退款额
        List<Order> todayVoided = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderStatus.VOIDED.name())
                .ge(Order::getVoidedAt, dayStart)
                .lt(Order::getVoidedAt, dayEnd));

        // 一次取全部相关订单项，避免 N+1
        Set<Long> orderIds = new HashSet<>();
        pendingOrders.forEach(o -> orderIds.add(o.getId()));
        preparingOrders.forEach(o -> orderIds.add(o.getId()));
        todayOrders.forEach(o -> orderIds.add(o.getId()));
        Map<Long, List<OrderItem>> itemMap = loadItems(orderIds);

        BoardVo board = new BoardVo();
        board.setPending(pendingOrders.stream()
                .map(order -> toPendingCard(order, itemMap.getOrDefault(order.getId(), Collections.emptyList()),
                        Duration.between(order.getPaidAt(), now).toMinutes()))
                .collect(Collectors.toList()));
        board.setPreparing(preparingOrders.stream()
                .map(order -> toPreparingCard(order, itemMap.getOrDefault(order.getId(), Collections.emptyList()),
                        Duration.between(order.getStartedAt(), now).toMinutes()))
                .collect(Collectors.toList()));
        board.setToday(buildToday(todayOrders, todayVoided, itemMap));
        return board;
    }

    /**
     * 开始制作：PAID → PREPARING，写 started_at（LLD 4.1）。
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminOrderSummaryVo start(Long orderId) {
        return transit(orderId, OrderStateMachine.Event.START_PREPARING);
    }

    /**
     * 出餐完成：PREPARING → COMPLETED，写 completed_at（LLD 4.1）。
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminOrderSummaryVo complete(Long orderId) {
        return transit(orderId, OrderStateMachine.Event.COMPLETE);
    }

    /**
     * 作废：PAID → VOIDED，写 voided_at / void_reason（LLD 4.1，仅未开始制作的单）。
     *
     * @param reason 作废原因（@Valid 已保证非空）
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminOrderSummaryVo voidOrder(Long orderId, String reason) {
        return transit(orderId, OrderStateMachine.Event.VOID, reason);
    }

    /**
     * 状态迁移统一实现：状态机硬校验 → 条件更新（防并发）→ 返回更新后摘要。
     */
    private AdminOrderSummaryVo transit(Long orderId, OrderStateMachine.Event event) {
        return transit(orderId, event, null);
    }

    private AdminOrderSummaryVo transit(Long orderId, OrderStateMachine.Event event, String voidReason) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "订单不存在");
        }
        OrderStatus from = OrderStateMachine.parse(order.getStatus());
        OrderStatus target = OrderStateMachine.next(from, event);

        LocalDateTime now = LocalDateTime.now();
        var update = Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, from.name())
                .set(Order::getStatus, target.name())
                .set(Order::getUpdatedAt, now);
        switch (event) {
            case START_PREPARING -> update.set(Order::getStartedAt, now);
            case COMPLETE -> update.set(Order::getCompletedAt, now);
            case VOID -> update.set(Order::getVoidedAt, now).set(Order::getVoidReason, voidReason);
            default -> throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(),
                    "订单状态冲突：" + event.getLabel() + " 不支持商家端操作");
        }

        int updated = orderMapper.update(null, update);
        if (updated == 0) {
            // 并发场景：状态已被另一请求推进（如双击「开始制作」）；本次事务回滚
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }
        log.info("[T14] 商家动作 {} orderId={} {} → {}", event.getLabel(), orderId, from, target);
        return summary(orderMapper.selectById(orderId));
    }

    /** 更新后订单摘要（含商品摘要）。 */
    private AdminOrderSummaryVo summary(Order order) {
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId())
                .orderByAsc(OrderItem::getId));

        AdminOrderSummaryVo vo = new AdminOrderSummaryVo();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setStatus(order.getStatus());
        vo.setPickupCode(order.getPickupCode());
        vo.setSource(order.getSource());
        vo.setItems(summarizeWithParen(items));
        vo.setTotalAmount(MoneyUtils.format(order.getTotalAmount()));
        vo.setPaidAt(format(order.getPaidAt()));
        vo.setStartedAt(format(order.getStartedAt()));
        vo.setCompletedAt(format(order.getCompletedAt()));
        vo.setVoidedAt(format(order.getVoidedAt()));
        vo.setVoidReason(order.getVoidReason());
        return vo;
    }

    private BoardPendingCardVo toPendingCard(Order order, List<OrderItem> items, long minutes) {
        BoardPendingCardVo card = new BoardPendingCardVo();
        card.setOrderId(order.getId());
        card.setPickupCode(order.getPickupCode());
        card.setSource(order.getSource());
        card.setItems(summarize(items));
        card.setTotalAmount(MoneyUtils.format(order.getTotalAmount()));
        card.setPaidAt(format(order.getPaidAt()));
        card.setMinutesWaiting(Math.max(minutes, 0));
        return card;
    }

    private BoardPreparingCardVo toPreparingCard(Order order, List<OrderItem> items, long minutes) {
        BoardPreparingCardVo card = new BoardPreparingCardVo();
        card.setOrderId(order.getId());
        card.setPickupCode(order.getPickupCode());
        card.setSource(order.getSource());
        card.setItems(summarize(items));
        card.setTotalAmount(MoneyUtils.format(order.getTotalAmount()));
        card.setStartedAt(format(order.getStartedAt()));
        card.setMinutesPreparing(Math.max(minutes, 0));
        return card;
    }

    /** 今日概览四数（SRS 6.5 口径，与 T34 统计接口一致）。 */
    private BoardTodayVo buildToday(List<Order> todayOrders, List<Order> todayVoided,
                                    Map<Long, List<OrderItem>> itemMap) {
        BigDecimal gross = BigDecimal.ZERO;
        long cupCount = 0;
        for (Order order : todayOrders) {
            gross = gross.add(order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount());
            for (OrderItem item : itemMap.getOrDefault(order.getId(), Collections.emptyList())) {
                cupCount += item.getQuantity() == null ? 0 : item.getQuantity();
            }
        }
        BigDecimal refund = BigDecimal.ZERO;
        for (Order order : todayVoided) {
            refund = refund.add(order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount());
        }

        BoardTodayVo today = new BoardTodayVo();
        today.setOrderCount(todayOrders.size());
        today.setAmount(MoneyUtils.format(gross.subtract(refund)));
        today.setCupCount(cupCount);
        today.setRefundAmount(MoneyUtils.format(refund));
        return today;
    }

    private Map<Long, List<OrderItem>> loadItems(Set<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<OrderItem> all = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .in(OrderItem::getOrderId, orderIds)
                .orderByAsc(OrderItem::getId));
        Map<Long, List<OrderItem>> map = new HashMap<>();
        for (OrderItem item : all) {
            map.computeIfAbsent(item.getOrderId(), k -> new ArrayList<>()).add(item);
        }
        return map;
    }

    /** 看板摘要："珍珠奶茶x1 大杯/少冰/珍珠"（LLD 3.5 示例格式）。 */
    private List<String> summarize(List<OrderItem> items) {
        List<String> lines = new ArrayList<>(items.size());
        for (OrderItem item : items) {
            StringBuilder sb = new StringBuilder()
                    .append(item.getProductName())
                    .append('x')
                    .append(item.getQuantity());
            String options = String.join("/", optionNames(item.getOptionsSnapshot()));
            if (!options.isEmpty()) {
                sb.append(' ').append(options);
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    /** 商家摘要（与顾客端列表同构）："珍珠奶茶x1(大杯/少冰/珍珠)"。 */
    private List<String> summarizeWithParen(List<OrderItem> items) {
        List<String> lines = new ArrayList<>(items.size());
        for (OrderItem item : items) {
            StringBuilder sb = new StringBuilder()
                    .append(item.getProductName())
                    .append('x')
                    .append(item.getQuantity());
            String options = String.join("/", optionNames(item.getOptionsSnapshot()));
            if (!options.isEmpty()) {
                sb.append('(').append(options).append(')');
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    private List<String> optionNames(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            OptionSnapshot[] arr = JSON.readValue(json, OptionSnapshot[].class);
            if (arr == null) {
                return Collections.emptyList();
            }
            return Arrays.stream(arr).map(OptionSnapshot::getOptionName).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[T14] 规格快照解析失败，按空规格处理：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATETIME_FMT);
    }
}
