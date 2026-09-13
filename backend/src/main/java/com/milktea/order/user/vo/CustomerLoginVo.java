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
}
