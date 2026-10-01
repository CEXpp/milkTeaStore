package com.milktea.order.order.vo;

/**
 * 「我已到店」申报结果（T49 到店握手）。
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param orderId   订单主键
 * @param arrivedAt 申报到店时间（yyyy-MM-dd HH:mm:ss）；重复申报时返回首次申报的时间
 * @param arrived   恒为 {@code true}（本响应只在申报成功后返回）
 */
public record ArrivalVo(Long orderId, String arrivedAt, boolean arrived) {
}
