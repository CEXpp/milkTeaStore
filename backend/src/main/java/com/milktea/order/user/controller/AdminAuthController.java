package com.milktea.order.user.controller;

import com.milktea.order.common.result.R;
import com.milktea.order.user.dto.AdminLoginRequest;
import com.milktea.order.user.service.AdminAuthService;
import com.milktea.order.user.vo.AdminLoginVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家认证接口。{@code /api/admin/login} 为白名单（无需 token，见 {@code JwtAuthenticationFilter}）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    @PostMapping("/login")
    public R<AdminLoginVo> login(@Valid @RequestBody AdminLoginRequest request) {
        return R.ok(adminAuthService.login(request.getUsername(), request.getPassword()));
    }
}
