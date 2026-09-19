package com.milktea.order.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.result.PageResult;
import com.milktea.order.common.util.MoneyUtils;
import com.milktea.order.order.dto.OptionSnapshot;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderItem;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.mapper.OrderItemMapper;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.order.vo.OrderDetailItemVo;
import com.milktea.order.order.vo.OrderDetailVo;
import com.milktea.order.order.vo.OrderListItemVo;
import com.milktea.order.order.vo.OrderStatusVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 订单查询服务（T13）：顾客端四个只读接口的统一实现（LLD 3.3）。
 *
 * <ul>
 *   <li>{@code GET /api/customer/orders/active}：进行中订单列表（PENDING_PAYMENT / PAID / PREPARING），
 *       供再扫码恢复场景；</li>
 *   <li>{@code GET /api/customer/orders}：历史分页（全部状态，创建时间倒序），
 *       响应结构 {list, total, page, size}（LLD 3.1）；</li>
 *   <li>{@code GET /api/customer/orders/{id}}：详情含完整订单项快照明细；</li>
 *   <li>{@code GET /api/customer/orders/{id}/status}：轮询专用轻量响应 {status, pickupCode, seq}。</li>
 * </ul>
 *
 * <p><b>归属校验</b>（LLD 9.1）：四个接口一律校验 {@code orders.customer_id = 当前 customerId}，
 * 他人订单返回 1005（不泄露订单内容）；订单不存在返回 1004。</p>
 *
 * <p><b>分页边界</b>：page 从 1 起（小于 1 归一为 1），size 默认 20、最大 100（越界归一化，
 * 不报错）——LLD 3.1 口径。单店量级下分页用 LIMIT 直接落库查询，无需缓存。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 进行中状态集合（LLD 3.3 active：待支付 / 已支付待制作 / 制作中）。 */
    private static final List<String> ACTIVE_STATUSES = List.of(
            OrderStatus.PENDING_PAYMENT.name(),
            OrderStatus.PAID.name(),
            OrderStatus.PREPARING.name());

    /** 分页默认每页条数与上限（LLD 3.1：默认 20，最大 100）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    /**
     * 进行中订单列表：PENDING_PAYMENT / PAID / PREPARING，创建时间倒序（最新在前）。
     */
    public List<OrderListItemVo> listActive(Long customerId) {
        List<Order> orders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getCustomerId, customerId)
                .in(Order::getStatus, ACTIVE_STATUSES)
                .orderByDesc(Order::getCreatedAt)
                .orderByDesc(Order::getId));
        return toListItems(orders);
    }

    /**
     * 历史订单分页（全部状态，创建时间倒序）。
     */
    public PageResult<OrderListItemVo> listHistory(Long customerId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        LambdaQueryWrapper<Order> base = new LambdaQueryWrapper<Order>()
                .eq(Order::getCustomerId, customerId);
        long total = orderMapper.selectCount(base);

        List<Order> orders = Collections.emptyList();
        if (total > 0) {
            long offset = (long) (safePage - 1) * safeSize;
            orders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                    .eq(Order::getCustomerId, customerId)
                    .orderByDesc(Order::getCreatedAt)
                    .orderByDesc(Order::getId)
                    .last("LIMIT " + offset + "," + safeSize));
        }
        return PageResult.of(toListItems(orders), total, safePage, safeSize);
    }

    /**
     * 订单详情（含订单项快照明细），归属校验 1005。
     */
    public OrderDetailVo detail(Long orderId, Long customerId) {
        Order order = requireOwnedOrder(orderId, customerId);
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId)
                .orderByAsc(OrderItem::getId));

        OrderDetailVo vo = new OrderDetailVo();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setSource(order.getSource());
        vo.setStatus(order.getStatus());
        vo.setPickupCode(order.getPickupCode());
        vo.setTotalAmount(MoneyUtils.format(order.getTotalAmount()));
        vo.setRemark(order.getRemark());
        vo.setCreatedAt(format(order.getCreatedAt()));
        vo.setPaidAt(format(order.getPaidAt()));
        vo.setStartedAt(format(order.getStartedAt()));
        vo.setCompletedAt(format(order.getCompletedAt()));
        vo.setClosedAt(format(order.getClosedAt()));
        vo.setVoidedAt(format(order.getVoidedAt()));
        vo.setVoidReason(order.getVoidReason());

        List<OrderDetailItemVo> itemVos = new ArrayList<>(items.size());
        for (OrderItem item : items) {
            itemVos.add(new OrderDetailItemVo(
                    item.getProductId(),
                    item.getProductName(),
                    item.getQuantity(),
                    MoneyUtils.format(item.getBasePrice()),
                    MoneyUtils.format(item.getUnitPrice()),
                    parseOptions(item.getOptionsSnapshot()),
                    MoneyUtils.format(item.getItemAmount())));
        }
        vo.setItems(itemVos);
        return vo;
    }

    /**
     * 轮询轻量状态：{status, pickupCode, seq}。
     *
     * <p>seq = 该单之前处于 PAID/PREPARING 的单数（LLD 3.3）：以本单支付时间为界，
     * 统计队列中更早支付且仍在等待/制作中的订单；未支付（无 paid_at）恒为 0。</p>
     */
    public OrderStatusVo status(Long orderId, Long customerId) {
        Order order = requireOwnedOrder(orderId, customerId);
        long seq = 0;
        if (order.getPaidAt() != null) {
            seq = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                    .in(Order::getStatus, List.of(OrderStatus.PAID.name(), OrderStatus.PREPARING.name()))
                    .lt(Order::getPaidAt, order.getPaidAt()));
        }
        return new OrderStatusVo(order.getStatus(), order.getPickupCode(), seq);
    }

    /**
     * 查单 + 归属校验：不存在 1004，非本人 1005（LLD 9.1）。
     */
    private Order requireOwnedOrder(Long orderId, Long customerId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT.getCode(), "订单不存在");
        }
        if (!Objects.equals(order.getCustomerId(), customerId)) {
            // 他人订单一律 1005，不泄露订单内容
            throw new BusinessException(ErrorCode.ORDER_NOT_BELONG);
        }
        return order;
    }

    /**
     * 批量组装列表项（含商品摘要），一次 IN 查询取全部订单项避免 N+1。
     */
    private List<OrderListItemVo> toListItems(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());
        List<OrderItem> allItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .in(OrderItem::getOrderId, orderIds)
                .orderByAsc(OrderItem::getId));
        Map<Long, List<OrderItem>> itemMap = new HashMap<>();
        for (OrderItem item : allItems) {
            itemMap.computeIfAbsent(item.getOrderId(), k -> new ArrayList<>()).add(item);
        }

        List<OrderListItemVo> list = new ArrayList<>(orders.size());
        for (Order order : orders) {
            OrderListItemVo vo = new OrderListItemVo();
            vo.setId(order.getId());
            vo.setStatus(order.getStatus());
            vo.setPickupCode(order.getPickupCode());
            vo.setTotalAmount(MoneyUtils.format(order.getTotalAmount()));
            vo.setCreatedAt(format(order.getCreatedAt()));
            vo.setItems(summarize(itemMap.getOrDefault(order.getId(), Collections.emptyList())));
            vo.setPaidAt(format(order.getPaidAt()));
            vo.setCompletedAt(format(order.getCompletedAt()));
            vo.setClosedAt(format(order.getClosedAt()));
            vo.setVoidedAt(format(order.getVoidedAt()));
            list.add(vo);
        }
        return list;
    }

    /** 商品摘要："珍珠奶茶x1(大杯/少冰/珍珠)"；无规格时省略括号段。 */
    private List<String> summarize(List<OrderItem> items) {
        List<String> lines = new ArrayList<>(items.size());
        for (OrderItem item : items) {
            StringBuilder sb = new StringBuilder()
                    .append(item.getProductName())
                    .append('x')
                    .append(item.getQuantity());
            List<OptionSnapshot> options = parseOptions(item.getOptionsSnapshot());
            if (!options.isEmpty()) {
                sb.append('(')
                        .append(options.stream().map(OptionSnapshot::getOptionName).collect(Collectors.joining("/")))
                        .append(')');
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    /** 反序列化规格快照 JSON；脏数据兜底为空列表（不影响列表展示）。 */
    private List<OptionSnapshot> parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            OptionSnapshot[] arr = JSON.readValue(json, OptionSnapshot[].class);
            return arr == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(arr));
        } catch (Exception e) {
            log.warn("[T13] 规格快照解析失败，按空规格处理：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATETIME_FMT);
    }
}
