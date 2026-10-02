package com.milktea.order.order.vo;

import java.util.List;

/**
 * 出餐核对清单（T58，W20）。
 *
 * <p><b>数据来源就是订单项快照本身</b>：{@code options} 直接取自
 * {@code order_item.options_snapshot}，不经过任何「摘要字符串」拼接——
 * 因此「清单与快照完全一致（不遗漏加料）」是取数路径决定的，不是靠人工保证（验收项）。</p>
 *
 * <p><b>这不是状态迁移的前置条件</b>：本 VO 只读；后端出餐仍按 6.1 原规则校验，
 * 勾选清单纯粹是前端的防错交互（任务卡「设计纪律」，已在文档中写明）。</p>
 *
 * <p>不可变数据传输类，按项目约定使用 record。</p>
 *
 * @param orderId        订单主键
 * @param orderNo        订单号
 * @param pickupCode     取餐码（核对时与杯子对照）
 * @param source         渠道
 * @param remark         整单口味备注（可为 null）
 * @param remarkTagCount 结构化备注标签数（T42 的 {@code orders.remark_tags}；T53 落地后才会有值）
 * @param items          订单项（含规格分组明细）
 */
public record OrderChecklistVo(
        Long orderId,
        String orderNo,
        String pickupCode,
        String source,
        String remark,
        int remarkTagCount,
        List<Item> items) {

    /**
     * 订单项。
     *
     * @param productName 商品名（下单时快照）
     * @param quantity    数量（杯数）
     * @param options     规格明细（按快照原序，含加料）
     * @param memberTag   团单成员标识（T64/W14，如「003 王工」）；非团单为 {@code null}。
     *                    出餐时逐行打勾核对，团单靠它辨归属；单人单为 {@code null} 即零干扰
     * @param description 制作指引（T60，W22）：<b>当前</b> {@code product.description}。
     *                    与商品名 / 规格不同，描述<b>不是快照</b>——商品改描述后历史订单
     *                    也会看到新描述，这是刻意取舍：SOP 需随做法演进而更新，冻结反而会让
     *                    店员照着过期做法做（任务卡「真店期招人后零成本获得培训材料」）。
     *                    商品已删除时为 {@code null}。
     */
    public record Item(String productName, int quantity, List<OptionLine> options,
                       String memberTag, String description) {
    }

    /**
     * 一行规格（核对时逐项打勾的最小单位）。
     *
     * @param groupName  规格组名（杯型 / 温度 / 甜度 / 加料）
     * @param optionName 选项名（大杯 / 少冰 / 珍珠 …）
     */
    public record OptionLine(String groupName, String optionName) {
    }
}
