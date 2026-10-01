package com.milktea.order.notify.vo;

/**
 * 可订阅的微信模板（T44）：供小程序拼 {@code uni.requestSubscribeMessage} 的 {@code tmplIds}。
 *
 * <p>模板 ID 只配置在服务端（与 AppSecret 同处），前端按需拉取——避免同一份 ID 在两端各写一份
 * 导致换模板时漏改。未配置模板时返回空列表，前端据此整体跳过授权（降级）。</p>
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param key        业务模板键（{@code PREPARING} / {@code PICKUP}），授权成功后原样回传服务端
 * @param templateId 微信模板 ID
 */
public record SubscribeTemplateVo(String key, String templateId) {
}
