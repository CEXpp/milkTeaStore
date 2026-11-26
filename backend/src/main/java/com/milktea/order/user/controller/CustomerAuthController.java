package com.milktea.order.user.controller;

import com.milktea.order.common.result.R;
import com.milktea.order.user.dto.CustomerLoginRequest;
import com.milktea.order.user.service.CustomerService;
import com.milktea.order.user.service.WxAuthService;
import com.milktea.order.user.vo.CustomerLoginVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 顾客认证接口。
 * <ul>
 *   <li>{@code POST /api/customer/login}：wx.login code → openid → 顾客 JWT（白名单，见 {@code JwtAuthenticationFilter}）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/customer")
@RequiredArgsConstructor
public class CustomerAuthController {

    private final WxAuthService wxAuthService;
    private final CustomerService customerService;

    @PostMapping("/login")
    public R<CustomerLoginVo> login(@Valid @RequestBody CustomerLoginRequest request) {
        String openid = wxAuthService.code2SessionOpenid(request.getCode());
        return R.ok(customerService.loginByOpenid(openid));
    }
}
