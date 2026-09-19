package com.milktea.order.order.service;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.order.OrderPayTestApplication;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.entity.OrderStatus;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.order.vo.PayVo;
import com.milktea.order.product.service.PricingService;
import com.milktea.order.shop.mapper.ShopConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T12 集成测试：Mock 支付与取餐码分配（LLD 4.1 迁移表首行 / 5.1 支付流程）。
 *
 * <p>跑在内存 H2（MySQL 模式）+ 真实事务上：orders / daily_seq 表结构与 V1__init_schema.sql 一致，
 * 因此「归属校验 → 状态机校验 → 策略支付 → 同事务写支付字段 + 取餐码」是端到端真实验证的，
 * 并发双击场景也走真实行锁与条件更新。</p>
 */
@ActiveProfiles("test")
// properties 为「内联测试属性」，优先级高于操作系统环境变量（CI 会把数据源指向 MySQL 容器，理由同 T11 的 SequenceServiceTest）
@SpringBootTest(
        classes = OrderPayTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:pay-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.hikari.maximum-pool-size=20",
                "payment.active-channel=mock"
        })
@DisplayName("T12 Mock 支付与取餐码分配")
class OrderServicePayTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 固定自然日：订单号前缀与取餐码流水日期均可预期。 */
    private static final LocalDate DAY = LocalDate.of(2026, 9, 12);
    private static final long CUSTOMER_A = 101L;
    private static final long CUSTOMER_B = 202L;
    private static final BigDecimal AMOUNT = new BigDecimal("17.00");

    /** 支付链路不触及计价与店铺开关：Mock 补位，避免测试上下文引入商品 / 店铺域。 */
    @MockitoBean
    private PricingService pricingService;

    @MockitoBean
    private ShopConfigMapper shopConfigMapper;

    @Autowired
    private OrderService orderService;

    @Autowired
    private SequenceService sequenceService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicInteger orderNoSeq = new AtomicInteger();

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS orders");
        jdbcTemplate.execute("DROP TABLE IF EXISTS daily_seq");
        jdbcTemplate.execute("""
                CREATE TABLE daily_seq (
                    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                    seq_date    DATE        NOT NULL,
                    seq_type    VARCHAR(16) NOT NULL,
                    current_seq INT         NOT NULL DEFAULT 0,
                    CONSTRAINT uk_date_type UNIQUE (seq_date, seq_type)
                )""");
        jdbcTemplate.execute("""
                CREATE TABLE orders (
                    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
                    order_no       VARCHAR(32)   NOT NULL,
                    source         VARCHAR(16)   NOT NULL,
                    status         VARCHAR(16)   NOT NULL,
                    customer_id    BIGINT        NULL,
                    pay_channel    VARCHAR(16)   NULL,
                    transaction_id VARCHAR(64)   NULL,
                    total_amount   DECIMAL(10,2) NOT NULL,
                    pickup_code    VARCHAR(8)    NULL,
                    remark         VARCHAR(255)  NULL,
                    paid_at        DATETIME      NULL,
                    started_at     DATETIME      NULL,
                    completed_at   DATETIME      NULL,
                    closed_at      DATETIME      NULL,
                    voided_at      DATETIME      NULL,
                    void_reason    VARCHAR(255)  NULL,
                    created_at     DATETIME      NOT NULL,
                    updated_at     DATETIME      NOT NULL,
                    CONSTRAINT uk_order_no UNIQUE (order_no)
                )""");
        orderNoSeq.set(0);
        sequenceService.useClock(fixedClock(DAY));
    }

    @Test
    @DisplayName("支付成功：状态 PAID + 写 pay_channel/transaction_id/paid_at + 取餐码 001")
    void payAssignsPickupCodeAndPayFields() {
        Order order = insertOrder(CUSTOMER_A, OrderStatus.PENDING_PAYMENT.name());

        PayVo vo = orderService.pay(order.getId(), CUSTOMER_A);

        assertEquals(order.getId(), vo.getId());
        assertEquals("PAID", vo.getStatus(), "支付后状态应为 PAID");
        assertEquals("001", vo.getPickupCode(), "首单取餐码应为 001");
        assertEquals("MOCK", vo.getPayChannel(), "支付渠道应为激活的 MOCK 实现");
        assertNotNull(vo.getPaidAt(), "响应应携带支付时间");

        Order row = orderMapper.selectById(order.getId());
        assertEquals("PAID", row.getStatus());
        assertEquals("MOCK", row.getPayChannel());
        assertTrue(row.getTransactionId().startsWith("MOCK-"), "交易号应为 Mock 生成");
        assertEquals(5 + 32, row.getTransactionId().length(), "交易号 = MOCK- + UUID 简写");
        assertNotNull(row.getPaidAt(), "paid_at 应落库");
        assertNotNull(row.getUpdatedAt());
        assertEquals("001", row.getPickupCode(), "取餐码应在 PAID 时落库");
        assertEquals(1, pickupSeq(), "当日取餐码流水应为 1");
    }

    @Test
    @DisplayName("连续支付两单：取餐码 001 后递增为 002")
    void pickupCodeIncrementsPerPaidOrder() {
        Order first = insertOrder(CUSTOMER_A, OrderStatus.PENDING_PAYMENT.name());
        Order second = insertOrder(CUSTOMER_A, OrderStatus.PENDING_PAYMENT.name());

        assertEquals("001", orderService.pay(first.getId(), CUSTOMER_A).getPickupCode());
        assertEquals("002", orderService.pay(second.getId(), CUSTOMER_A).getPickupCode());
        assertEquals(2, pickupSeq());
    }

    @Test
    @DisplayName("重复支付：1004，且不重复吃号、不改支付信息")
    void duplicatePayRejectedWith1004() {
        Order order = insertOrder(CUSTOMER_A, OrderStatus.PENDING_PAYMENT.name());
        PayVo paid = orderService.pay(order.getId(), CUSTOMER_A);

        BusinessException ex = assertBusinessCode(ErrorCode.ORDER_STATUS_CONFLICT, () -> orderService.pay(order.getId(), CUSTOMER_A));

        // T14 起状态校验统一走 OrderStateMachine，1004 文案带上下文（「订单状态冲突：PAID 状态下不允许『支付成功』」）。
        // 错误码语义不变，此处只断言语义前缀——文案属人读信息、允许可读性演进。
        assertTrue(ex.getMessage().startsWith("订单状态冲突"), "实际文案：" + ex.getMessage());
        Order row = orderMapper.selectById(order.getId());
        assertEquals("001", row.getPickupCode(), "重复支付不得改取餐码");
        assertEquals(paid.getPaidAt(), row.getPaidAt(), "重复支付不得改支付时间");
        assertEquals(1, pickupSeq(), "重复支付不得重复吃号");
    }

    @Test
    @DisplayName("支付他人订单：1005，订单保持待支付且未分配取餐码")
    void payOthersOrderRejectedWith1005() {
        Order order = insertOrder(CUSTOMER_A, OrderStatus.PENDING_PAYMENT.name());

        assertBusinessCode(ErrorCode.ORDER_NOT_BELONG, () -> orderService.pay(order.getId(), CUSTOMER_B));

        Order row = orderMapper.selectById(order.getId());
        assertEquals(OrderStatus.PENDING_PAYMENT.name(), row.getStatus());
        assertNull(row.getPickupCode());
        assertNull(row.getPayChannel());
        assertEquals(0, pickupSeq(), "校验失败不应分配取餐码");
    }

    @Test
    @DisplayName("非待支付订单（已关闭）：1004")
    void payClosedOrderRejectedWith1004() {
        Order order = insertOrder(CUSTOMER_A, OrderStatus.CLOSED.name());

        assertBusinessCode(ErrorCode.ORDER_STATUS_CONFLICT, () -> orderService.pay(order.getId(), CUSTOMER_A));

        assertEquals(OrderStatus.CLOSED.name(), orderMapper.selectById(order.getId()).getStatus());
        assertEquals(0, pickupSeq());
    }

    @Test
    @DisplayName("订单不存在：1004")
    void payMissingOrderRejectedWith1004() {
        BusinessException ex = assertBusinessCode(ErrorCode.ORDER_STATUS_CONFLICT, () -> orderService.pay(999_999L, CUSTOMER_A));
        assertEquals("订单不存在", ex.getMessage());
    }

    @Test
    @DisplayName("并发双击支付：恰好一次成功（取餐码 001），其余全部 1004，只消耗一个号")
    void concurrentDoublePayOnlyOneSucceeds() throws InterruptedException {
        Order order = insertOrder(CUSTOMER_A, OrderStatus.PENDING_PAYMENT.name());
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<PayVo> successes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Integer> conflicts = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        successes.add(orderService.pay(order.getId(), CUSTOMER_A));
                    } catch (BusinessException e) {
                        conflicts.add(e.getCode());
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发支付未在 30 秒内完成");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(errors.isEmpty(), () -> "并发支付出现异常：" + errors.peek());
        assertEquals(1, successes.size(), "并发双击应恰好一次成功，实际 " + successes.size() + " 次");
        assertEquals(threads - 1, conflicts.size(), "其余请求应全部被拒");
        assertTrue(conflicts.stream().allMatch(code -> code == ErrorCode.ORDER_STATUS_CONFLICT.getCode()),
                "拒绝码应全为 1004，实际：" + conflicts);
        assertEquals("001", successes.peek().getPickupCode());
        assertEquals(1, pickupSeq(), "失败事务应回滚取餐码，当日只消耗一个号");
        assertEquals(OrderStatus.PAID.name(), orderMapper.selectById(order.getId()).getStatus());
    }

    /** 插入一笔待支付订单（模拟 T10 下单产物；本卡只关注支付段）。 */
    private Order insertOrder(long customerId, String status) {
        Order order = new Order();
        order.setOrderNo("260912" + String.format("%05d", orderNoSeq.incrementAndGet()));
        order.setSource("MINI_PROGRAM");
        order.setStatus(status);
        order.setCustomerId(customerId);
        order.setTotalAmount(AMOUNT);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.insert(order);
        return order;
    }

    /** 当日已发放的取餐码流水（无行返回 0）。 */
    private int pickupSeq() {
        List<Integer> seqs = jdbcTemplate.queryForList(
                "SELECT current_seq FROM daily_seq WHERE seq_date = ? AND seq_type = 'PICKUP_CODE'",
                Integer.class, DAY);
        return seqs.isEmpty() ? 0 : seqs.get(0);
    }

    private BusinessException assertBusinessCode(ErrorCode expected, org.junit.jupiter.api.function.Executable executable) {
        BusinessException ex = assertThrows(BusinessException.class, executable);
        assertEquals(expected.getCode(), ex.getCode());
        return ex;
    }

    private static Clock fixedClock(LocalDate date) {
        return Clock.fixed(date.atStartOfDay(ZONE).toInstant(), ZONE);
    }
}
