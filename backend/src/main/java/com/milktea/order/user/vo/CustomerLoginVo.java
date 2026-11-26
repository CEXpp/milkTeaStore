package com.milktea.order.user.vo;

import lombok.Data;

/**
 * 顾客登录返回（供小程序端持久化 token 与展示身份）。
 */
@Data
public class CustomerLoginVo {

    private String token;

    private Long customerId;

    private String openid;

    private String nickname;

    /**
     * 头像代理路径 {@code /api/files/{key}}（本轮新增，向后兼容的追加字段）。
     *
     * <p>登录时一并回传，是为了让小程序启动即拿到头像，不必再多发一次资料请求
     * ——个人中心的头部在弱网下少一次往返就少一次空白。</p>
     */
    private String avatarUrl;
}
