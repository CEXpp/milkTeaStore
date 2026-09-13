package com.milktea.order.user.vo;

import lombok.Data;

/**
 * 商家登录返回（供前端 Pinia 持久化 token 与展示昵称）。
 */
@Data
public class AdminLoginVo {

    private String token;

    private Long adminId;

    private String username;

    private String nickname;
}
