package com.milktea.order.order.vo;

import com.milktea.order.shop.vo.SlaSettingsVo;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 订单看板全量轮询响应（T14，LLD 3.5 {@code GET /api/admin/orders/board}）。
 *
 * <p>商家端每 3 秒全量拉取：双分区卡片 + 今日概览；前端比对前后两次 pending 的
 * orderId 差集判断新单（播提示音 + 高亮，LLD 4.4）。</p>
 *
 * <p>T52 起随响应附带 {@link #sla}（SLA 预警阈值）——这样阈值改动后，商家端起下一次
 * 看板刷新即可生效，<b>无需新增任何查询接口</b>。</p>
 */
@Data
public class BoardVo implements Serializable {

    /** 已支付待制作（PAID），按支付时间正序（T51 起：超 SLA 转红者置顶、已申报到店时间者次优先）。 */
    private List<BoardPendingCardVo> pending;

    /** 制作中（PREPARING），按开始时间正序。 */
    private List<BoardPreparingCardVo> preparing;

    /** 今日概览（SRS 6.5 口径）。 */
    private BoardTodayVo today;

    /** SLA 预警阈值（T52）：转黄 / 转红秒数，来自 shop_config。 */
    private SlaSettingsVo sla;
}
