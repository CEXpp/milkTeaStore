package com.milktea.order.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * T12 支付测试专用最小启动配置：加载 order 域（OrderService / SequenceService 与 orders / daily_seq Mapper）
 * 与 payment 域（PaymentService + MockPaymentProvider），不加载 Web / 鉴权 / 商品等无关组件，
 * 避免单测依赖 profile 密钥与外部容器（与 T11 的 SequenceTestApplication 同套路）。
 *
 * <p>OrderService 还依赖计价与店铺开关（下单链路），由测试类以 {@code @MockitoBean} 补位。</p>
 */
@SpringBootApplication(scanBasePackages = {"com.milktea.order.order", "com.milktea.order.payment"})
@MapperScan("com.milktea.order.order.mapper")
public class OrderPayTestApplication {
}
