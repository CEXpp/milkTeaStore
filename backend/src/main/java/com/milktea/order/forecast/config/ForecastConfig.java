package com.milktea.order.forecast.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 爆单预测配置装配（T69）：启用 {@code forecast.*} 配置绑定。 */
@Configuration
@EnableConfigurationProperties(ForecastProperties.class)
public class ForecastConfig {
}