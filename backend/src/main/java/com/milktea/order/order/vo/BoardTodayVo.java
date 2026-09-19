package com.milktea.order.order.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 看板今日概览（T14，LLD 3.5 board → today；口径 SRS 6.5）：
 * 订单数 / 营业额 / 杯数 / 退款额。
 *
 * <p>【施工登记】T14 任务卡要求「今日概览四数（订单数/金额/杯数/退款额）」，
 * LLD 3.5 示例只列前三项；此处按任务卡补齐 refundAmount，属向后兼容的增字段
 * （不改动既有字段语义），账台统计页（T34/T35）口径与之一致。</p>
 *
 * <ul>
 *   <li>orderCount：当日进入过「已支付」及以后状态的订单数（含作废，不含超时关闭）；</li>
 *   <li>amount：当日已支付订单实付合计 − 当日作废退款额；</li>
 *   <li>cupCount：主饮品件数（加料、规格不另计，即 order_item.quantity 合计）；</li>
 *   <li>refundAmount：当日作废订单的实付金额合计（单独列示）。</li>
 * </ul>
 */
@Data
public class BoardTodayVo implements Serializable {

    private long orderCount;

    /** 营业额（两位小数字符串）。 */
    private String amount;

    private long cupCount;

    /** 今日退款额（两位小数字符串）。 */
    private String refundAmount;
}
