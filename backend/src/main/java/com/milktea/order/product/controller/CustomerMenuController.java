package com.milktea.order.product.controller;

import com.milktea.order.common.result.R;
import com.milktea.order.product.service.MenuService;
import com.milktea.order.product.vo.MenuVo;
import com.milktea.order.shop.vo.ShopStatusVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 顾客端菜单接口（公开，无需 token）。
 */
@RestController
@RequestMapping("/api/customer")
@RequiredArgsConstructor
public class CustomerMenuController {

    private final MenuService menuService;

    @GetMapping("/menu")
    public R<MenuVo> menu() {
        return R.ok(menuService.getMenu());
    }

    @GetMapping("/shop-status")
    public R<ShopStatusVo> shopStatus() {
        return R.ok(menuService.getShopStatus());
    }
}
