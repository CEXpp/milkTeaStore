package com.milktea.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类：位于 {@code com.milktea.order} 根包，向下扫描 common / product / order / shop 等全部业务包。
 *
 * <p>{@code @EnableScheduling}：启用心跳任务（T15 超时关单；后续 AI 会话清理同用）。</p>
 */
@EnableScheduling
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}
