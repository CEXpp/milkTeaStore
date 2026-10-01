package com.milktea.order.order.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 看板「待制作」卡片（T14，LLD 3.5 {@code GET /api/admin/orders/board} → pending[]）：
 * 已在 PAID 状态等待制作的订单，按支付时间正序（先付先做）。
 *
 * <pre>
 * { "orderId": 3001, "pickupCode": "018", "source": "AI",
 *   "items": [ "珍珠奶茶x1 大杯/少冰/五分糖/珍珠" ],
 *   "totalAmount": "17.00", "paidAt": "2026-09-12 14:22:10", "minutesWaiting": 3 }
 * </pre>
 */
@Data
public class BoardPendingCardVo implements Serializable {

    private Long orderId;

    private String pickupCode;

    /** 来源渠道：MINI_PROGRAM / AI / COUNTER（看板渠道标签）。 */
    private String source;

    /** 商品摘要："商品名x数量 规格/规格"（空格分隔，与看板契约一致）。 */
    private List<String> items;

    private String totalAmount;

    private String paidAt;

    /** 已等待分钟数（now - paidAt，含边界归零）。 */
    private long minutesWaiting;

    /**
     * 顾客是否已申报到店（T49 到店握手）。
     *
     * <p>仅作**提示**：卡片会打上「已到店」标记、排序保持不变（验收项「不强制改排序」）。
     * 店长可视情况优先处理，也可以完全无视——系统不会因此调整任何队列顺序或统计口径。</p>
     */
    private boolean arrived;

    /** 申报到店时间（yyyy-MM-dd HH:mm:ss）；未申报为 null。 */
    private String arrivedAt;
}
