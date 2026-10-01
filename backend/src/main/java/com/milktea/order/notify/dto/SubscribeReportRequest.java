package com.milktea.order.notify.dto;

import java.util.List;

/**
 * 订阅授权上报（T44）：小程序 {@code uni.requestSubscribeMessage} 成功后把用户同意订阅的
 * 模板回传服务端。
 *
 * <p><b>为什么必须回传</b>：微信只告知客户端「用户点了同意」，服务端无从得知；
 * 而一次性订阅的「可下发次数」记在服务端（{@code wx_subscribe_quota}）。
 * 不回传就意味着「用户授权了、服务端却不知道」，推送会因无额度而被跳过。</p>
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param accepted 用户同意订阅的业务模板键（{@code PREPARING} / {@code PICKUP}）；
 *                 用户拒绝的模板不出现在此列表，服务端不做任何登记（即验收项「拒绝授权不影响全流程」）
 */
public record SubscribeReportRequest(List<String> accepted) {
}
