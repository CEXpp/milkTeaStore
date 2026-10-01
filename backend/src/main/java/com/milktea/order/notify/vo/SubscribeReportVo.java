package com.milktea.order.notify.vo;

/**
 * 订阅授权上报结果（T44）。
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param granted        本次登记成功的模板数（即 {@code accepted} 中合法键的个数）
 * @param remainingTotal 登记后该顾客剩余可下发总次数（供前端判断「是否还需要再次请求授权」）
 */
public record SubscribeReportVo(int granted, int remainingTotal) {
}
