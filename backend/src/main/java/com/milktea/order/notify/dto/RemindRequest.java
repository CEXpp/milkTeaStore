package com.milktea.order.notify.dto;

/**
 * 稍后提醒登记请求（T47 下单前预期管理）。
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param delayMinutes 延迟分钟数；为空或非正数时取服务端默认（{@code order.remind-delay-minutes}），
 *                     并受服务端上限保护（见 {@code RemindTaskService.MAX_DELAY_MINUTES}）
 */
public record RemindRequest(Integer delayMinutes) {
}
