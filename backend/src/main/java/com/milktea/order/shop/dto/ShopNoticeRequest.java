package com.milktea.order.shop.dto;

import jakarta.validation.constraints.Size;

/**
 * 营业公告请求（T62，v1 底座 F06 {@code PUT /api/admin/shop/notice}）。
 *
 * <p><b>可清空</b>：传空串或null 即「撤下公告」。这是与「暂停提示语」的关键区别——
 * 后者只在暂停时才有意义，前者要能独立发布与撤下。</p>
 *
 * <p><b>60 字而非 64</b>：公告会在顾客端菜单顶部整条展示，比暂停提示语更需要克制；
 * 60 字是一句话能说清「今日 XX / 预计 XX 点恢复」的舒适上限。</p>
 *
 * @param notice 公告正文，最多 60 字；空串或null 表示撤下公告
 */
public record ShopNoticeRequest(
        @Size(max = 60, message = "公告过长（最多 60 字）")
        String notice) {
}