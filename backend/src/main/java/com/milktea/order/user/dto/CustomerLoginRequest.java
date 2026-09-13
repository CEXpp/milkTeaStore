package com.milktea.order.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 顾客登录请求：携带微信 {@code wx.login} 获取的临时登录 code。
 * <p>code 经后端调 jscode2session 换取 openid（见 {@code WxAuthService}）。</p>
 */
@Data
public class CustomerLoginRequest {

    @NotBlank(message = "登录 code 不能为空")
    private String code;
}
