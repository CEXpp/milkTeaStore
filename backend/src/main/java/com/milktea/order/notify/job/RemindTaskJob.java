package com.milktea.order.notify.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.milktea.order.notify.config.WxSubscribeProperties;
import com.milktea.order.notify.entity.RemindTask;
import com.milktea.order.notify.mapper.RemindTaskMapper;
import com.milktea.order.notify.service.WxSubscribeMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 稍后提醒派发（T47）。每分钟扫描到期的 {@code remind_task}，经 T44 的订阅消息通道下发。
 *
 * <p><b>与 T44 的关系</b>：本任务不新建推送设施，只复用 {@link WxSubscribeMessageService#deliver}——
 * 「占额度 → 取 access_token → POST → 失败退额度」那套语义与订单推送完全一致，
 * 差别仅在触发源（这里是到点扫描，那里是订单事件）。</p>
 *
 * <p><b>一次性语义</b>：无论下发成功还是被跳过，任务都标记为终态并写 {@code handled_at}——
 * 微信一次性订阅本来就只有一次机会，无限重试既无意义又会持续占用调度。</p>
 *
 * <p><b>降级</b>：订阅消息未启用 / 用户没授权 / 额度已用完 → 任务记为 {@code SKIPPED}。
 * 顾客端的购物车数据始终留在本地，不因提醒成败受到任何影响（任务卡「提醒送达后购物车内容不丢失」）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemindTaskJob {

    /** 单轮处理上限：防止长时间停机后一次涌入过多任务把调度线程占满。 */
    private static final int BATCH_SIZE = 50;

    /** 提醒文案（模板 {@code thing} 类变量上限 20 字符，此处 8 字）。 */
    private static final String REMIND_TEXT = "现在下单等待更短";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final RemindTaskMapper remindTaskMapper;
    private final WxSubscribeMessageService subscribeMessageService;
    private final WxSubscribeProperties properties;

    /**
     * 派发到期提醒：{@code fixedDelay} 60 秒（上一轮结束后计时，不叠加）。
     */
    @Scheduled(fixedDelay = 60_000)
    public void dispatchDueReminders() {
        List<RemindTask> due = remindTaskMapper.selectList(new LambdaQueryWrapper<RemindTask>()
                .eq(RemindTask::getStatus, RemindTask.STATUS_PENDING)
                .le(RemindTask::getRemindAt, LocalDateTime.now())
                .orderByAsc(RemindTask::getRemindAt)
                .last("LIMIT " + BATCH_SIZE));
        if (due.isEmpty()) {
            return;
        }
        for (RemindTask task : due) {
            handle(task);
        }
        log.info("[T47] 稍后提醒本轮处理 {} 条", due.size());
    }

    /** 处理单条任务：下发（可能被降级跳过）→ 置终态。 */
    private void handle(RemindTask task) {
        WxSubscribeProperties.Template template = properties.templateFor(WxSubscribeProperties.KEY_REMIND);
        Map<String, Object> data = buildData(template);
        boolean sent = false;
        if (!data.isEmpty()) {
            sent = subscribeMessageService.deliver(task.getCustomerId(), WxSubscribeProperties.KEY_REMIND,
                    data, properties.page(), "remindTaskId=" + task.getId());
        }
        task.setStatus(sent ? RemindTask.STATUS_SENT : RemindTask.STATUS_SKIPPED);
        task.setHandledAt(LocalDateTime.now());
        task.setNote(sent ? "已下发" : "未下发（订阅消息未启用 / 无可用额度 / 下发失败）");
        remindTaskMapper.updateById(task);
    }

    /**
     * 组装提醒模板数据。
     *
     * <p>提醒场景没有订单，故只填「状态文案」与「时间」两类变量；取餐码变量在此不适用
     * （模板里若配了该变量，本条消息就不下发它——{@code RemindTaskService} 不做任何订单关联）。</p>
     */
    private Map<String, Object> buildData(WxSubscribeProperties.Template template) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (template == null) {
            return data;
        }
        if (StringUtils.hasText(template.statusKey())) {
            data.put(template.statusKey().trim(), Map.of("value", REMIND_TEXT));
        }
        if (StringUtils.hasText(template.timeKey())) {
            data.put(template.timeKey().trim(), Map.of("value", LocalDateTime.now().format(TIME_FMT)));
        }
        return data;
    }
}
