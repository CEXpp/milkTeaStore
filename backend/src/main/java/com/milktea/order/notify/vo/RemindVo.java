package com.milktea.order.notify.vo;

/**
 * 稍后提醒登记结果（T47）。
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param taskId       提醒任务主键（前端可用于「已登记」幂等展示，也便于排查）
 * @param remindAt     计划提醒时间（yyyy-MM-dd HH:mm:ss）
 * @param delayMinutes 实际生效的延迟分钟数（服务端归一后的值，非请求原值）
 */
public record RemindVo(Long taskId, String remindAt, int delayMinutes) {
}
