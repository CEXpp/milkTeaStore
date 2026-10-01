package com.milktea.order.shop.vo;

/**
 * 看板 SLA 预警阈值（T52，W21 / v1 底座 F05）。
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param warnSeconds   转黄阈值（秒）：已等待达到即转为黄色提示
 * @param dangerSeconds 转红阈值（秒）：已等待达到即转为红色<b>并置顶</b>
 */
public record SlaSettingsVo(int warnSeconds, int dangerSeconds) {
}
