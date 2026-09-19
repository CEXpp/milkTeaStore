package com.milktea.order.order;

import com.milktea.order.order.service.SequenceService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * T11 单测专用最小启动配置：自动装配数据源 / MyBatis-Plus / 事务，
 * 但只加载 order 域（{@link SequenceService} 与 daily_seq Mapper），
 * 不加载 Web / 鉴权 / 商品等无关组件，避免单测依赖 profile 密钥与外部容器。
 */
@SpringBootApplication(scanBasePackages = "com.milktea.order.order")
@MapperScan("com.milktea.order.order.mapper")
public class SequenceTestApplication {
}
