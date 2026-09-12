package com.milktea.order.common.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：统一扫描各域 mapper（product / order / shop ...）。
 */
@Configuration
@MapperScan("com.milktea.order.**.mapper")
public class MybatisPlusConfig {
}
