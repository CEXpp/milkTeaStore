package com.milktea.order.order.controller;

import com.milktea.order.common.result.R;
import com.milktea.order.order.dto.OrderCreateRequest;
import com.milktea.order.order.service.OrderService;
import com.milktea.order.order.vo.OrderCreateVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 顾客端订单接口（T10）：创建订单（状态 PENDING_PAYMENT），需顾客 JWT。
 */
@RestController
@RequestMapping("/api/customer")
@RequiredArgsConstructor
public class CustomerOrderController {

    private final OrderService orderService;

    /**
     * POST /api/customer/orders —— 创建订单。
     */
    @PostMapping("/orders")
    public R<OrderCreateVo> createOrder(@Valid @RequestBody OrderCreateRequest request) {
        return R.ok(orderService.createOrder(request));
    }
}
