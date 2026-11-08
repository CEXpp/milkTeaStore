package com.milktea.order.user.controller;

import com.milktea.order.common.result.R;
import com.milktea.order.user.service.CustomerService;
import com.milktea.order.user.vo.CustomerLoginVo;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仅供本地联调的免微信登录入口；需显式开启且仅在 dev profile 注册。 */
@Profile("dev")
@ConditionalOnProperty(name = "app.dev-login-enabled", havingValue = "true")
@RestController
@RequestMapping("/api/customer")
@RequiredArgsConstructor
public class DevCustomerAuthController {

    private final CustomerService customerService;

    @GetMapping("/dev-login")
    public R<CustomerLoginVo> devLogin(@RequestParam("openid") String openid) {
        return R.ok(customerService.loginByOpenid(openid));
    }
}
