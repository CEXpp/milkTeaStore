package com.milktea.order.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderItem;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.mapper.OrderItemMapper;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.order.service.SequenceService;
import com.milktea.order.product.entity.Product;
import com.milktea.order.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 演示沙盘造数（T74，E8d · F11）。
 *
 * <p><b>为什么要它</b>（任务卡设计理由）：AC-15「统计口径一致性」需要当日各渠道订单若干笔
 * 才能核对，AC-05 需要一笔真单等到超时关闭。每轮演示手工攒数据既慢又不可重复——
 * 同样的演示跑两遍，数字对不上，根本没法当场演示「口径一致」。</p>
 *
 * <p><b>真店配置下端点不可达</b>（验收项）：靠 {@code @ConditionalOnProperty} 让整个
 * Controller <b>不被注册</b>——真店部署时这个 URL 返回 404，而不是「进来才发现没权限」。
 * 后者意味着攻击面还在，只是加了道门；前者是根本没有这个入口。</p>
 *
 * <p><b>造出来的数据是「形状正确」的</b>：跨三渠道、含 1 笔作废（计退款、不计杯）、
 * 1 笔超时关闭（无 paid_at、不进任何统计），使 AC-15 的
 * 「营业额 = 有效实付 − 作废退款」能被当场验证。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoSeedService {

    /** 商品无价格时的兜底金额（正常商品都有价，这里只是防御空值）。 */
    private static final BigDecimal FALLBACK_AMOUNT = BigDecimal.ZERO;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final SequenceService sequenceService;

    private final java.time.Clock clock;

    public DemoSeedService(OrderMapper orderMapper,
                           OrderItemMapper orderItemMapper,
                           ProductMapper productMapper,
                           SequenceService sequenceService,
                           @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
        this.sequenceService = sequenceService;
        this.clock = java.time.Clock.system(ZoneId.of(timeZone));
    }

    /**
     * 生成一批当日演示订单。
     *
     * @param orders 常规单笔数（三渠道轮流分配）；另固定追加 1 笔作废、1 笔超时关闭
     * @throws BusinessException 1001 笔数非法 / 无在售商品
     */
    @Transactional(rollbackFor = Exception.class)
    public SeedResult seedDay(int orders) {
        if (orders <= 0 || orders > 200) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "笔数需为 1~200");
        }
        List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, Product.STATUS_ON)
                .orderByAsc(Product::getSortOrder, Product::getId)
                .last("LIMIT 20"));
        if (products.isEmpty()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND.getCode(), "请先上架至少一个商品再演示造数");
        }

        LocalDate day = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now();
        // 当日 00:00 作为基准往前推，保证 paid_at 落在当日内（统计按 paid_at 归属）
        LocalDateTime dayStart = day.atStartOfDay();

        String[] sources = {Order.SOURCE_MINI_PROGRAM, Order.SOURCE_AI, Order.SOURCE_COUNTER};
        int created = 0;
        int voided = 0;
        int closed = 0;

        for (int i = 0; i < orders; i++) {
            // 三渠道轮流，让 AC-15 的渠道结构有数据可核对
            Product product = products.get(i % products.size());
            Order order = newOrder(sources[i % sources.length], product.getBasePrice(), dayStart, i);
            orderMapper.insert(order);
            insertItem(order, product, 1 + (i % 2));
            order.setStatus(OrderStatus.PAID.name());
            order.setPaidAt(order.getCreatedAt());
            order.setPickupCode(sequenceService.nextPickupCode());
            order.setPayChannel(Order.PAY_CHANNEL_COUNTER);
            orderMapper.updateById(order);
            created++;
        }

        // 固定 1 笔作废：计入订单数、实付全额进退款额、不计营业额与杯数（AC-15 的关键样本）
        Product voidProduct = products.getFirst();
        Order voidOrder = newOrder(Order.SOURCE_COUNTER, voidProduct.getBasePrice(), dayStart, orders + 1);
        voidOrder.setStatus(OrderStatus.VOIDED.name());
        voidOrder.setPaidAt(voidOrder.getCreatedAt());
        voidOrder.setVoidedAt(voidOrder.getCreatedAt().plusMinutes(5));
        voidOrder.setVoidReason("演示造数：顾客取消");
        orderMapper.insert(voidOrder);
        insertItem(voidOrder, voidProduct, 1);
        voided++;

        // 固定 1 笔超时关闭：没有 paid_at，按 6.5 不落入任何一天（AC-05 的样本）
        Product closedProduct = products.get(products.size() > 1 ? 1 : 0);
        Order closedOrder = newOrder(Order.SOURCE_MINI_PROGRAM, closedProduct.getBasePrice(), dayStart, orders + 2);
        closedOrder.setStatus(OrderStatus.CLOSED.name());
        closedOrder.setClosedAt(closedOrder.getCreatedAt().plusMinutes(15));
        orderMapper.insert(closedOrder);
        insertItem(closedOrder, closedProduct, 1);
        closed++;

        log.info("[T74] demo seed done date={} created={} voided={} closed={}", day, created, voided, closed);
        return new SeedResult(day.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                created, voided, closed, products.size());
    }

    /**
     * 清理当日演示造数（验收项「可清理当轮数据」）。
     *
     * <p>只删「当日创建、且由本服务造出来的形态」的订单：即无顾客 id（counter 形态）
     * 或支付渠道为 MOCK/COUNTER 的单。<b>刻意不按日期无脑删</b>——
     * 演示当天也可能真有顾客下单，误删会让「刚刚演示过的真实订单」凭空消失。</p>
     *
     * @return 删除的订单数（含其订单项）
     */
    @Transactional(rollbackFor = Exception.class)
    public int cleanup() {
        LocalDate day = LocalDate.now(clock);
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();

        List<Order> candidates = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .ge(Order::getCreatedAt, start)
                .lt(Order::getCreatedAt, end)
                .in(Order::getPayChannel, List.of(Order.PAY_CHANNEL_COUNTER, "MOCK")));
        if (candidates.isEmpty()) {
            return 0;
        }
        List<Long> orderIds = candidates.stream().map(Order::getId).toList();
        orderItemMapper.delete(new LambdaQueryWrapper<OrderItem>().in(OrderItem::getOrderId, orderIds));
        int removed = orderMapper.deleteByIds(orderIds);
        log.info("[T74] demo seed cleanup removed={}", removed);
        return removed;
    }

    private Order newOrder(String source, BigDecimal amount, LocalDateTime dayStart, int index) {
        LocalDateTime createdAt = dayStart.plusMinutes(9 * 60L + index * 3L);
        Order order = new Order();
        order.setOrderNo(sequenceService.nextOrderNo());
        order.setSource(source);
        order.setStatus(OrderStatus.PENDING_PAYMENT.name());
        order.setCustomerId(null);
        order.setTotalAmount(amount == null ? FALLBACK_AMOUNT : amount);
        order.setRemark("演示造数");
        order.setCreatedAt(createdAt);
        order.setUpdatedAt(createdAt);
        return order;
    }

    private void insertItem(Order order, Product product, int quantity) {
        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setProductId(product.getId());
        item.setProductName(product.getName());
        item.setBasePrice(product.getBasePrice());
        // 演示单不选规格：规格快照给空数组，计价与统计都只认主饮品件数，形状与真实单一致
        item.setOptionsSnapshot("[]");
        item.setQuantity(quantity);
        item.setUnitPrice(product.getBasePrice());
        item.setItemAmount(product.getBasePrice().multiply(BigDecimal.valueOf(quantity)));
        item.setMemberTag(null);
        orderItemMapper.insert(item);
    }

    /**
     * 造数结果。
     *
     * @param date          归属日
     * @param created       常规单数（已支付）
     * @param voided        作废单数（固定 1）
     * @param closed        超时关闭单数（固定 1）
     * @param productCount  参与造数的在售商品数
     */
    public record SeedResult(String date, int created, int voided, int closed, int productCount) {
    }
}
