package com.milktea.order.order.vo;

import java.util.List;

/**
 * 每一杯茶的制作档案（T59，W21）。
 *
 * <p><b>只读档案，无编辑入口</b>：本 VO 仅由 {@code GET /api/admin/brew-archives} 返回，
 * 后端不存在任何写入档案的接口（验收项「无编辑入口」由接口形状保证，而非靠前端不渲染按钮）。</p>
 *
 * <p><b>「不可篡改」的兑现方式</b>：本 VO 的每个字段都取自下单瞬间锁定的快照列
 * （{@code order_item} 的规格与金额、{@code orders} 的六个时间戳），商品后续改价改规格
 * 不会回溯到历史档案——与 6.3「价格不回溯」同源，扩展为「整份档案不回溯」。</p>
 *
 * <p><b>不含个人信息</b>：档案只承载「杯」与「单」的制作事实，不含顾客昵称、手机号、
 * openid 等任何身份信息（与 T73 对账凭证「导出不含个人信息」同一纪律）。</p>
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param orderItemId   订单项主键（= 一份档案的稳定标识；「每一杯」即每一行）
 * @param orderId订单主键
 * @param orderNo       订单号
 * @param pickupCode    取餐码（未支付单为 null）
 * @param source        下单渠道 MINI_PROGRAM / AI / COUNTER
 * @param status        订单状态（终态判定依据，非法值不在此产生）
 * @param productId     商品 id（指向 <b>当前</b> 商品，可能已下架/改名，与 productName 快照不一致是正常的）
 * @param productName   商品名<b>快照</b>（下单瞬间的名字，商品改名后仍为旧名）
 * @param quantity      杯数（同项多杯共用一份档案）
 * @param basePrice     基础价快照（两位小数字符串）
 * @param unitPrice     单价快照 = 基础价 + Σ 价差（两位小数字符串）
 * @param itemAmount    单项金额快照 = 单价 × 杯数（两位小数字符串）
 * @param options       规格快照明细（按快照原序，含加料；解析失败为空列表而非报错）
 * @param optionSummary 规格一行摘要（仅供列表速览，<b>不得</b>用作核对依据）
 * @param remark        整单口味备注（可为空）
 * @param remarkTagCount结构化备注标签数（T53 落地后有值；只报条数不解释语义，同 T58 纪律）
 * @param voidReason    作废原因（仅作废单有值）
 * @param createdAt     下单时间
 * @param paidAt        支付时间
 * @param startedAt     开始制作时间
 * @param completedAt   出餐时间
 * @param closedAt      超时关闭时间
 * @param voidedAt      作废时间
 * @param prepMinutes   本单制作耗时（分钟；未完成时为 null）
 * @param belongDate    归属日 yyyy-MM-dd（锚点为 paid_at，未支付单回落到 created_at）
 */
public record BrewArchiveVo(
        Long orderItemId,
        Long orderId,
        String orderNo,
        String pickupCode,
        String source,
        String status,
        Long productId,
        String productName,
        int quantity,
        String basePrice,
        String unitPrice,
        String itemAmount,
        List<OptionLine> options,
        String optionSummary,
        String remark,
        int remarkTagCount,
        String voidReason,
        String createdAt,
        String paidAt,
        String startedAt,
        String completedAt,
        String closedAt,
        String voidedAt,
        Integer prepMinutes,
        String belongDate) {

    /**
     * 一行规格（快照原样呈现）。
     *
     * @param groupName  规格组名（杯型 / 温度 / 甜度 / 加料）
     * @param optionName 选项名（大杯 / 少冰 / 珍珠 …）
     * @param priceDelta 价差（两位小数字符串；无规格时为 null）
     */
    public record OptionLine(String groupName, String optionName, String priceDelta) {
    }
}