package com.milktea.order.order.vo;

/**
 * 到店预约申报结果（T51，W02「我将到」）。
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param orderId    订单主键
 * @param etaMinutes 生效的预计到店时长（分钟，3 / 5 / 10）；撤销后为 {@code null}
 */
public record ArrivalEtaVo(Long orderId, Integer etaMinutes) {
}
