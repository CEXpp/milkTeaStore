package com.milktea.order.order.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 看板「制作中」卡片（T14，LLD 3.5 {@code GET /api/admin/orders/board} → preparing[]）：
 * PREPARING 状态的订单，按开始制作时间正序。
 *
 * <pre>
 * { "orderId": 2999, "pickupCode": "017", "source": "MINI_PROGRAM",
 *   "items": [ "四季春茶x2 中杯/冰" ],
 *   "totalAmount": "38.00", "startedAt": "2026-09-12 14:18:40", "minutesPreparing": 5 }
 * </pre>
 */
@Data
public class BoardPreparingCardVo implements Serializable {

    private Long orderId;

    private String pickupCode;

    private String source;

    /** 商品摘要："商品名x数量 规格/规格"。 */
    private List<String> items;

    private String totalAmount;

    private String startedAt;

    /** 已制作分钟数（now - startedAt，含边界归零）。 */
    private long minutesPreparing;
}
