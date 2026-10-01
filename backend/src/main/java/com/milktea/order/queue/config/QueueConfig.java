package com.milktea.order.queue.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 队列预估内核配置装配（T46 / T48）：启用 {@code queue.*} 与 {@code queue.pace.*} 配置绑定。
 */
@Configuration
@EnableConfigurationProperties({QueueProperties.class, QueuePaceProperties.class})
public class QueueConfig {
}
