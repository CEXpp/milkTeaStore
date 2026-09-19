package com.milktea.order.order.service;

import com.milktea.order.order.SequenceTestApplication;
import com.milktea.order.order.entity.DailySeq;
import com.milktea.order.order.mapper.DailySeqMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T11 流水号服务单元测试（订单号 / 取餐码）。
 *
 * <p>跑在内存 H2（MySQL 模式）+ 真实事务上：表结构与 V1__init_schema.sql 的 daily_seq 一致，
 * 因此「行锁递增 + 同事务回读」的并发行为是可被真实验证的，而不是靠 mock 假象。</p>
 */
@ActiveProfiles("test")
// properties 为「内联测试属性」，优先级高于操作系统环境变量：CI 会把 SPRING_DATASOURCE_URL /
// USERNAME / PASSWORD 指向 MySQL 服务容器（供 dev 档的 BackendApplicationTests 使用），
// 若不在此显式钉住，application-test.yml 里的 H2 数据源会被这些环境变量覆盖，
// 出现「H2 驱动 + MySQL URL」而无法建连。此处取值与 src/test/resources/application-test.yml 保持一致。
@SpringBootTest(
        classes = SequenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:sequence-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
@DisplayName("T11 流水号服务（订单号 / 取餐码）")
class SequenceServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY_1 = LocalDate.of(2026, 9, 12);
    private static final LocalDate DAY_2 = DAY_1.plusDays(1);

    @Autowired
    private SequenceService sequenceService;

    @Autowired
    private DailySeqMapper dailySeqMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS daily_seq");
        jdbcTemplate.execute("""
                CREATE TABLE daily_seq (
                    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                    seq_date    DATE        NOT NULL,
                    seq_type    VARCHAR(16) NOT NULL,
                    current_seq INT         NOT NULL DEFAULT 0,
                    CONSTRAINT uk_date_type UNIQUE (seq_date, seq_type)
                )""");
        sequenceService.useClock(fixedClock(DAY_1));
    }

    @Test
    @DisplayName("无初始行时自动补插：首個取餐码 001，首个订单号 yyMMdd+00001")
    void firstSequenceStartsFromOne() {
        assertEquals("001", sequenceService.nextPickupCode());
        assertEquals("26091200001", sequenceService.nextOrderNo());

        List<DailySeq> rows = dailySeqMapper.selectList(null);
        assertEquals(2, rows.size(), "ORDER_NO / PICKUP_CODE 各占一行");
    }

    @Test
    @DisplayName("并发 50 线程取取餐码：不重号且恰好覆盖 001-050")
    void concurrentPickupCodeNeverDuplicates() throws InterruptedException {
        List<String> codes = runConcurrently(50, sequenceService::nextPickupCode);

        assertEquals(50, codes.size());
        assertEquals(50, new HashSet<>(codes).size(), "取餐码出现重号：" + codes);

        List<String> sorted = codes.stream().sorted().toList();
        assertEquals("001", sorted.get(0));
        assertEquals("050", sorted.get(49));
        assertEquals(1, dailySeqMapper.selectList(null).size(), "同一自然日只应有一行流水");
    }

    @Test
    @DisplayName("并发 50 线程取订单号：不重号且均为当日前缀 yyMMdd + 5 位")
    void concurrentOrderNoNeverDuplicates() throws InterruptedException {
        List<String> orderNos = runConcurrently(50, sequenceService::nextOrderNo);

        assertEquals(50, new HashSet<>(orderNos).size(), "订单号出现重号：" + orderNos);
        assertTrue(orderNos.stream().allMatch(no -> no.startsWith("260912")), "订单号日期前缀错误");
        assertTrue(orderNos.stream().allMatch(no -> no.matches("\\d{11}")), "订单号应为 11 位数字");

        List<String> sorted = orderNos.stream().sorted().toList();
        assertEquals("26091200001", sorted.get(0));
        assertEquals("26091200050", sorted.get(49));
    }

    @Test
    @DisplayName("跨自然日重置：换日从 001 / 00001 重新开始，回到旧日沿用原流水")
    void sequenceResetsOnNextDay() {
        assertEquals("001", sequenceService.nextPickupCode());
        assertEquals("002", sequenceService.nextPickupCode());
        assertEquals("26091200001", sequenceService.nextOrderNo());
        assertEquals("26091200002", sequenceService.nextOrderNo());

        sequenceService.useClock(fixedClock(DAY_2));
        assertEquals("001", sequenceService.nextPickupCode(), "跨日后取餐码应重置为 001");
        assertEquals("002", sequenceService.nextPickupCode());
        assertEquals("26091300001", sequenceService.nextOrderNo(), "跨日后订单号序列应重置");

        sequenceService.useClock(fixedClock(DAY_1));
        assertEquals("003", sequenceService.nextPickupCode(), "回到旧日应沿用原流水，不得重置");
        assertEquals("26091200003", sequenceService.nextOrderNo());

        assertEquals(2, countRows("PICKUP_CODE"), "PICKUP_CODE 应有两日各一行");
        assertEquals(2, countRows("ORDER_NO"), "ORDER_NO 应有两日各一行");
    }

    @Test
    @DisplayName("取餐码超过 999 自然进位为四位")
    void pickupCodeOverflowsToFourDigits() {
        jdbcTemplate.update("INSERT INTO daily_seq (seq_date, seq_type, current_seq) VALUES (?, ?, ?)",
                DAY_1, SequenceService.SeqType.PICKUP_CODE.name(), 999);

        assertEquals("1000", sequenceService.nextPickupCode());
        assertEquals("1001", sequenceService.nextPickupCode());
    }

    @Test
    @DisplayName("两种流水互不影响：同一日分类型各自行锁递增")
    void pickupCodeAndOrderNoUseIndependentRows() {
        assertEquals("001", sequenceService.nextPickupCode());
        assertEquals("002", sequenceService.nextPickupCode());
        assertEquals("26091200001", sequenceService.nextOrderNo());
        assertEquals("26091200002", sequenceService.nextOrderNo());
        assertEquals("003", sequenceService.nextPickupCode(), "取餐码不受订单号序列影响");
        assertEquals("26091200003", sequenceService.nextOrderNo(), "订单号不受取餐码序列影响");
    }

    private int countRows(String seqType) {
        Set<String> dates = new HashSet<>();
        dailySeqMapper.selectList(null).stream()
                .filter(row -> seqType.equals(row.getSeqType()))
                .forEach(row -> dates.add(String.valueOf(row.getSeqDate())));
        return dates.size();
    }

    /**
     * 多线程同时取号：主线程统一放行后由线程池并发调用服务，收集全部返回与异常。
     */
    private List<String> runConcurrently(int threads, Supplier<String> task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<String> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        results.add(task.get());
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发取号未在 30 秒内完成");
        } finally {
            pool.shutdownNow();
        }
        assertTrue(failures.isEmpty(), () -> "并发取号出现异常：" + failures.peek());
        return new ArrayList<>(results);
    }

    private static Clock fixedClock(LocalDate date) {
        return Clock.fixed(date.atStartOfDay(ZONE).toInstant(), ZONE);
    }
}
