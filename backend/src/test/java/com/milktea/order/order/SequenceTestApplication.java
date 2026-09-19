package com.milktea.order.order;

import com.milktea.order.order.service.SequenceService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * T11 单测专用最小启动配置：自动装配数据源 / MyBatis-Plus / 事务，
 * 但只注册 order 域的取号组件（{@link SequenceService} 与 daily_seq Mapper），
 * 不加载 Web / 鉴权 / 商品等无关组件，避免单测依赖 profile 密钥与外部容器。
 *
 * <p>此处刻意不做包扫描：同包的 OrderService（T10 下单 / T12 支付）还依赖商品、店铺、支付域，
 * 逐个排除易随域扩展反复失效，显式 {@link Import} 才与本测试的关注点一致。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(SequenceService.class)
@MapperScan("com.milktea.order.order.mapper")
public class SequenceTestApplication {
}
