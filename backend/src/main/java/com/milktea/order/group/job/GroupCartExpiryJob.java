package com.milktea.order.group.job;

import com.milktea.order.group.service.GroupCartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 拼单到期清理任务（T63）。
 *
 * <p><b>为什么需要它</b>：池的「不可支付」判定靠 {@code expires_at > NOW()} 写在 CAS 条件里，
 * 数据库已经能挡住过期池被支付；但若不把状态刷成 {@code EXPIRED}，列表里会一直挂着
 * 形同僵尸的「收单中」拼单，成员点进去才发现已结束。</p>
 *
 * <p><b>不承担正确性责任</b>：即便本任务没跑、跑晚了，超期池也付不掉——
 * 正确性由 CAS 条件保证，本任务只负责状态的可见性与整洁。</p>
 *
 * <p>沿用 T15 的调度基础设施（{@code @Scheduled(fixedDelay)} + {@code @EnableScheduling}），
 * 单店量级下每分钟一次、单批 200 条即可，不引入 Quartz 或分布式锁。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupCartExpiryJob {

    private final GroupCartService groupCartService;

    /** 每分钟扫一次到期未转单的拼单，批量置 EXPIRED。 */
    @Scheduled(fixedDelay = 60_000)
    public void expireOverdue() {
        try {
            groupCartService.expireOverdue();
        } catch (Exception e) {
            // 单次清理失败不应中断调度（否则后续所有批次都不再执行）
            log.warn("[T63] 拼单到期清理失败：{}", e.getMessage());
        }
    }
}