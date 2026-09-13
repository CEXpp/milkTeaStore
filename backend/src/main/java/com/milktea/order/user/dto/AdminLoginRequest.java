package com.milktea.order.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 商家登录请求。
 */
@Data
public class AdminLoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
