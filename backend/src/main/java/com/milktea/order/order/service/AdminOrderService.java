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
import com.milktea.order.order.event.OrderEventPublisher;
import com.milktea.order.order.mapper.OrderItemMapper;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.order.vo.AdminOrderSummaryVo;
import com.milktea.order.order.vo.BoardPendingCardVo;
import com.milktea.order.order.vo.BoardPreparingCardVo;
import com.milktea.order.order.vo.BoardTodayVo;
import com.milktea.order.order.vo.BoardVo;
import com.milktea.order.order.vo.OrderChecklistVo;
import com.milktea.order.shop.service.SlaSettingsService;
import com.milktea.order.shop.vo.SlaSettingsVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
 *
 * <p><b>实时事件（T43，LLD 11.1）</b>：三个状态动作落定后经 {@link OrderEventPublisher} 发布
 * （PREPARING / COMPLETED / VOIDED），出餐额外追加 PICKUP_READY；事务内发布、提交后投递。</p>
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
    private final OrderEventPublisher eventPublisher;
    private final SlaSettingsService slaSettingsService;

    /**
     * 看板全量查询（3 秒轮询）：双分区卡片 + 今日概览四数。
     */
    public BoardVo board() {
        LocalDateTime now = LocalDateTime.now();

        // SLA 阈值（T52）：先取出来——它既参与排序（转红置顶），也要随响应下发给前端
        SlaSettingsVo sla = slaSettingsService.current();

        List<Order> pendingOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderStatus.PAID.name()));
        // 排序改到内存（T51/T52）：规则含「转红置顶」与「已申报到店时间优先」，
        // 用 Comparator 比拼 SQL 直观；单店量级下内存排序没有性能压力。
        pendingOrders.sort(pendingComparator(sla.dangerSeconds(), now));
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
        board.setSla(sla);
        return board;
    }

    /**
     * 待制作分区的排序（T51 / T52）。
     *
     * <pre>
     *   1) 已等待超过 danger 阈值的<b>置顶</b>（T52 验收「超 danger 转红并置顶」）
     *   2) 其余中：已申报预计到店时长的在前，按到达临近度升序（3 &lt; 5 &lt; 10 分钟）（T51）
     *   3) 最后按 paid_at 升序、id 升序 —— 即 LLD 3.5 的「先付先做」
     * </pre>
     *
     * <p>第 3 条保证「不申报时与现状一致」：所有订单 {@code etaMinutes} 均为 null 且无人超 danger 时，
     * 本比较器退化为纯粹的 paid_at + id 排序，与改动前<b>逐字相同</b>（T51 验收项）。</p>
     *
     * <p>SLA 优先于到店预约：SLA 是硬指标（顾客已经等太久了），到店预约只是「建议顺序」。</p>
     *
     * <p>注意 {@code arrivedAt}（「我已到店」）<b>不</b>参与本排序——那是 T49 的验收要求。</p>
     */
    private Comparator<Order> pendingComparator(int dangerSeconds, LocalDateTime now) {
        return Comparator
                .comparingInt((Order order) -> isSlaDanger(order, dangerSeconds, now) ? 0 : 1)
                .thenComparingInt((Order order) -> order.getEtaMinutes() == null ? 1 : 0)
                .thenComparing(Order::getEtaMinutes, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                .thenComparing(Order::getPaidAt, Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()))
                .thenComparing(Order::getId);
    }

    /**
     * 是否已超 SLA 转红阈值（T52）。
     *
     * <p>只看「已等待时长」，不做任何状态判定、不写任何字段——因此与 6.1 状态流转完全无关
     * （任务卡验收「与 6.1 状态流转不冲突」）。</p>
     */
    private boolean isSlaDanger(Order order, int dangerSeconds, LocalDateTime now) {
        return order.getPaidAt() != null
                && Duration.between(order.getPaidAt(), now).getSeconds() >= dangerSeconds;
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
        // 六态之 PREPARING / COMPLETED / VOIDED：发布状态变更；出餐再加一条 PICKUP_READY（LLD 11.1）
        Order updatedOrder = orderMapper.selectById(orderId);
        eventPublisher.publishStatusChanged(updatedOrder);
        if (target == OrderStatus.COMPLETED) {
            eventPublisher.publishPickupReady(updatedOrder);
        }
        return summary(updatedOrder);
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

    /**
     * 出餐核对清单（T58，W20）。
     *
     * <p><b>只读</b>：不改订单状态、不做任何校验判定——出餐仍由 {@link #complete(Long)} 按 6.1
     * 原规则走状态机。勾选清单纯粹是前端的防错交互（任务卡「确认不是状态迁移的前置条件」）。</p>
     *
     * <p>规格明细直接取自 {@code order_item.options_snapshot}，<b>不经过任何摘要字符串拼接</b>，
     * 因此不会「漏掉加料」（验收项「清单与快照完全一致」）。</p>
     *
     * @throws BusinessException 1004 订单不存在
     */
    public OrderChecklistVo checklist(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "订单不存在");
        }
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId)
                .orderByAsc(OrderItem::getId));

        List<OrderChecklistVo.Item> itemVos = new ArrayList<>(items.size());
        for (OrderItem item : items) {
            itemVos.add(new OrderChecklistVo.Item(
                    item.getProductName(),
                    item.getQuantity() == null ? 0 : item.getQuantity(),
                    optionLines(item.getOptionsSnapshot())));
        }
        return new OrderChecklistVo(orderId, order.getOrderNo(), order.getPickupCode(), order.getSource(),
                order.getRemark(), countRemarkTags(order.getRemarkTags()), itemVos);
    }

    /** 规格快照 → 逐行规格（保留分组名，前端据此把「加料」等易漏项标出来）。 */
    private List<OrderChecklistVo.OptionLine> optionLines(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            OptionSnapshot[] arr = JSON.readValue(json, OptionSnapshot[].class);
            if (arr == null) {
                return Collections.emptyList();
            }
            List<OrderChecklistVo.OptionLine> lines = new ArrayList<>(arr.length);
            for (OptionSnapshot option : arr) {
                lines.add(new OrderChecklistVo.OptionLine(option.getGroupName(), option.getOptionName()));
            }
            return lines;
        } catch (Exception e) {
            log.warn("[T58] 规格快照解析失败，核对清单按空规格返回：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 结构化备注标签条数（T42 的 {@code orders.remark_tags}；T53 落地前恒为 0）。 */
    private int countRemarkTags(String json) {
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isArray() ? node.size() : 0;
        } catch (Exception e) {
            log.warn("[T58] 备注标签解析失败，按 0 处理：{}", e.getMessage());
            return 0;
        }
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
        // 到店预约（T51）：「我将到」参与建议排序，故随卡片一并下发给前端展示标识
        card.setEtaMinutes(order.getEtaMinutes());
        // 到店握手（T49）：「我已到店」只打标记，不参与排序（验收项「不强制改排序」）
        card.setArrived(order.getArrivedAt() != null);
        card.setArrivedAt(format(order.getArrivedAt()));
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
