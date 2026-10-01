package com.milktea.order.order.dto;

/**
 * 到店预约申报请求（T51，W02「我将到」）。
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param etaMinutes 预计到店时长（分钟）：仅支持 {@code 3 / 5 / 10}；
 *                   传 {@code null} 表示<b>撤销</b>申报（任务卡验收「可改可撤」）
 */
public record OrderEtaRequest(Integer etaMinutes) {
}
