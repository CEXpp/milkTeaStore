package com.milktea.order.shop.vo;

/**
 * 营业公告响应（T62，v1 底座 F06）。
 *
 * <p>{@code notice} 为<b>已归一化</b>的公告：库里存空白时统一回 {@code null}，
 * 让前端只需判一次 {@code notice != null} 即可决定是否渲染公告条——
 * 不必同时处理 {@code null} / {@code ""} / 纯空格三种「等于没有」的情况。</p>
 *
 * @param notice 公告正文；未发布或已撤下时为 {@code null}
 */
public record ShopNoticeVo(String notice) {
}