package com.milktea.order.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.notify.entity.RemindTask;
import com.milktea.order.notify.mapper.RemindTaskMapper;
import com.milktea.order.notify.vo.RemindVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 稍后提醒登记与查询（T47 下单前预期管理）。
 *
 * <h2>关键约束的落地</h2>
 * <p>任务卡要求本需求「发生在创建订单之前，不改变 3.5 流程与 6.1 超时关单规则」，并把它列为验收项
 * 「选『稍后提醒』不产生任何订单」。本服务因此<b>只写 {@code remind_task} 一张表</b>：
 * 不碰 {@code orders}、不分配取餐码、不进入 4.1 状态机、不参与 6.5 统计——
 * 从数据层面就不存在「选提醒却下了单」的可能。</p>
 *
 * <p>同理，本需求<b>不涉及任何价格优惠</b>（SRS 非目标表封锁了利益补偿路径），
 * 只提供信息透明：把队列压力提前告诉顾客，由顾客自己决定何时下单。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemindTaskService {

    /**
     * 延迟上限（分钟）。
     *
     * <p>本需求语义是「现在排队久，过会儿再来」，因此提醒应落在一次消费决策的合理窗口内；
     * 超过上限的请求会被截断到上限，避免登记出「明天再提醒」这类不属于本需求的任务。</p>
     */
    public static final int MAX_DELAY_MINUTES = 60;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RemindTaskMapper remindTaskMapper;

    /** 默认延迟（分钟）：与下单支付期限同量级，见 {@code order.remind-delay-minutes}。 */
    @Value("${order.remind-delay-minutes:15}")
    private int defaultDelayMinutes;

    /**
     * 登记一条稍后提醒。
     *
     * <p><b>幂等</b>：同一顾客已有待发任务时只<b>顺延</b>其时间，不新增任务——
     * 否则顾客连点几次就会攒下一串提醒（每一条都会消耗一次一次性订阅额度）。</p>
     *
     * @param customerId   当前登录顾客
     * @param delayMinutes 请求的延迟分钟数；为空 / 非正数取默认值，超过上限截断到上限
     */
    @Transactional(rollbackFor = Exception.class)
    public RemindVo schedule(Long customerId, Integer delayMinutes) {
        int delay = normalizeDelay(delayMinutes);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime remindAt = now.plusMinutes(delay);

        RemindTask pending = findPending(customerId);
        if (pending != null) {
            pending.setRemindAt(remindAt);
            remindTaskMapper.updateById(pending);
            log.info("[T47] 稍后提醒已顺延 customerId={} taskId={} remindAt={}", customerId, pending.getId(), remindAt);
            return new RemindVo(pending.getId(), remindAt.format(FMT), delay);
        }

        RemindTask task = new RemindTask();
        task.setCustomerId(customerId);
        task.setRemindAt(remindAt);
        task.setStatus(RemindTask.STATUS_PENDING);
        task.setCreatedAt(now);
        remindTaskMapper.insert(task);
        log.info("[T47] 稍后提醒已登记 customerId={} taskId={} remindAt={}（未产生任何订单）",
                customerId, task.getId(), remindAt);
        return new RemindVo(task.getId(), remindAt.format(FMT), delay);
    }

    /**
     * 查询当前待发提醒（供前端展示「已登记，将于 X 提醒你」）。
     *
     * @return 无待发提醒时返回 {@code null}
     */
    public RemindVo pending(Long customerId) {
        RemindTask task = findPending(customerId);
        if (task == null) {
            return null;
        }
        long minutes = Math.max(0, Duration.between(LocalDateTime.now(), task.getRemindAt()).toMinutes());
        return new RemindVo(task.getId(), task.getRemindAt().format(FMT), (int) minutes);
    }

    private RemindTask findPending(Long customerId) {
        return remindTaskMapper.selectOne(new LambdaQueryWrapper<RemindTask>()
                .eq(RemindTask::getCustomerId, customerId)
                .eq(RemindTask::getStatus, RemindTask.STATUS_PENDING)
                .orderByDesc(RemindTask::getId)
                .last("LIMIT 1"));
    }

    /** 延迟归一：非正数取默认，超上限截断，下限 1 分钟。 */
    private int normalizeDelay(Integer requested) {
        int delay = (requested == null || requested <= 0) ? defaultDelayMinutes : requested;
        if (delay < 1) {
            delay = 1;
        }
        return Math.min(delay, MAX_DELAY_MINUTES);
    }
}
