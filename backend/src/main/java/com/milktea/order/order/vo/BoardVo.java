package com.milktea.order.order.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 订单看板全量轮询响应（T14，LLD 3.5 {@code GET /api/admin/orders/board}）。
 *
 * <p>商家端每 3 秒全量拉取：双分区卡片 + 今日概览；前端比对前后两次 pending 的
 * orderId 差集判断新单（播提示音 + 高亮，LLD 4.4）。</p>
 */
@Data
public class BoardVo implements Serializable {

    /** 已支付待制作（PAID），按支付时间正序。 */
    private List<BoardPendingCardVo> pending;

    /** 制作中（PREPARING），按开始时间正序。 */
    private List<BoardPreparingCardVo> preparing;

    /** 今日概览（SRS 6.5 口径）。 */
    private BoardTodayVo today;
}
