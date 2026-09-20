package com.milktea.order.shop.controller;

import com.milktea.order.common.result.R;
import com.milktea.order.shop.dto.ShopPauseRequest;
import com.milktea.order.shop.service.ShopConfigService;
import com.milktea.order.shop.vo.ShopPauseVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端门店开关（T23，LLD 3.5.5，商家 JWT）。
 *
 * <p>{@code PUT /api/admin/shop/pause}：开启后顾客端下单接口返回 1006，
 * 顾客端菜单 / shop-status 的 paused 变为 true；<b>进行中订单不受影响</b>——
 * 看板、状态流转与出餐照常（暂停只在「下单」入口拦截，见 OrderService）。</p>
 */
@RestController
@RequestMapping("/api/admin/shop")
@RequiredArgsConstructor
public class AdminShopController {

    private final ShopConfigService shopConfigService;

    /** 暂停 / 恢复接单：body {@code {paused, notice}}，响应 {@code {paused}}。 */
    @PutMapping("/pause")
    public R<ShopPauseVo> pause(@Valid @RequestBody ShopPauseRequest request) {
        boolean paused = shopConfigService.updatePause(request.getPaused(), request.getNotice());
        return R.ok(new ShopPauseVo(paused));
    }
}
