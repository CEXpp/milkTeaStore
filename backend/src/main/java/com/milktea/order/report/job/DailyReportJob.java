package com.milktea.order.report.job;

import com.milktea.order.report.config.DailyReportProperties;
import com.milktea.order.report.service.DailyReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日经营日报生成任务（T68）。
 *
 * <p>复用排期 T15 的调度基础设施（{@code @Scheduled} + {@code @EnableScheduling}），
 * 单店量级下每分钟探一次「是不是到了打烊时刻」即可，不引入 Quartz 或分布式锁。</p>
 *
 * <p><b>为什么用「探测 + 幂等重跑」而不是「每天 22:00 定点触发一次」</b>：
 * 定点触发若那一刻应用正好在重启/发布，那一整天的日报就永远缺了。
 * 探测式则会在恢复后自动补上——重跑同一日只会覆盖同一行（{@code uk_report_date}），
 * 因此重复执行是安全的。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReportJob {

    private final DailyReportService dailyReportService;
    private final DailyReportProperties properties;

    /** 打烊时刻生成（默认 22 点）。每分钟探测一次，命中即生成当日日报。 */
    @Scheduled(cron = "0 * * * * *")
    public void generateAtClosing() {
        int closingHour = properties.closingHourOrDefault();
        int currentHour = java.time.LocalTime.now().getHour();
        if (currentHour != closingHour) {
            return;
        }
        try {
            // 重跑安全：同一日只覆盖同一行。因此本任务哪怕在一小时内被触发多次也无害。
            dailyReportService.generate(null);
        } catch (Exception e) {
            // 单次生成失败不应中断调度（否则后续日子都不再生成）
            log.warn("[T68] 日报生成失败：{}", e.getMessage());
        }
    }
}