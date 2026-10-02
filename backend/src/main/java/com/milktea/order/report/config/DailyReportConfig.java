package com.milktea.order.report.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 日报配置装配（T68）：启用 {@code report.*} 配置绑定。
 *
 * <p>与 {@code QueueConfig} 同一写法——项目内不用 {@code @ConfigurationPropertiesScan}，
 * 而是逐域显式 {@code @EnableConfigurationProperties}，好处是「哪个域绑了哪些配置」
 * 在配置类上一眼可查。</p>
 */
@Configuration
@EnableConfigurationProperties(DailyReportProperties.class)
public class DailyReportConfig {
}