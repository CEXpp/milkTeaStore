package com.milktea.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类：位于 {@code com.milktea.order} 根包，向下扫描 common / product / order / shop / ai 等全部业务包。
 *
 * <p>{@code @EnableScheduling} 依 LLD 4.3「应用启动类加 {@code @EnableScheduling}」开启，
 * 当前供 AI 会话过期草稿惰性清理使用（T29，每分钟一次），后续由超时关单调度（T15）复用。</p>
 */
@SpringBootApplication
@EnableScheduling
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}
