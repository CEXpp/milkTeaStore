package com.milktea.order.shop.dto;

/**
 * 看板 SLA 预警阈值设置请求（T52）。
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param warnSeconds   转黄阈值（秒）；为空或非正数取默认
 * @param dangerSeconds 转红阈值（秒）；须大于转黄阈值，否则服务端按 warn + 60 归一
 */
public record SlaSettingsRequest(Integer warnSeconds, Integer dangerSeconds) {
}
