package com.milktea.order.user.vo;

/**
 * 顾客资料视图（GET / PUT /api/customer/profile、POST /api/customer/profile/avatar 响应 data）。
 *
 * <p><b>为什么同时给 key 与 url</b>：与商品 VO（{@code imageKey} + {@code imageUrl}）保持一致。
 * 前端渲染用 {@code avatarUrl}（相对路径，由前端按服务端基址拼绝对地址，与商品图同一套处理），
 * {@code avatarKey} 则便于联调时对着 MinIO 桶核对「这张图到底存没存进去」。</p>
 *
 * @param customerId 顾客主键
 * @param nickname   昵称；未设置时为 null
 * @param avatarKey  头像对象 key（MinIO）；未设置时为 null
 * @param avatarUrl  头像对外代理路径 {@code /api/files/{key}}；未设置时为 null
 */
public record CustomerProfileVo(Long customerId, String nickname, String avatarKey, String avatarUrl) {
}
