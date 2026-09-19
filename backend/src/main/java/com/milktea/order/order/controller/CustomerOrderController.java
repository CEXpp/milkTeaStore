package com.milktea.order.order.controller;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.common.result.R;
import com.milktea.order.order.service.OrderService;
import com.milktea.order.order.vo.PayVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 顾客订单接口（/api/customer/orders/**，顾客 JWT，见 JwtAuthenticationFilter 路径矩阵）。
 *
 * <ul>
 *   <li>{@code POST /api/customer/orders/{id}/pay}（T12）：Mock 支付成功 → 分配取餐码 → 状态 PAID；</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/customer/orders")
@RequiredArgsConstructor
public class CustomerOrderController {

    private final OrderService orderService;

    /**
     * 发起支付（LLD 3.3）：归属校验 1005、状态冲突 1004 由服务层抛出，经全局异常处理器返回。
     */
    @PostMapping("/{id}/pay")
    public R<PayVo> pay(@PathVariable("id") Long id) {
        return R.ok(orderService.pay(id, currentCustomerId()));
    }

    /** 当前登录顾客 id：由 JWT 过滤器在请求作用域内绑定（防御性判空兜底 401）。 */
    private Long currentCustomerId() {
        AuthContext.Principal principal = AuthContext.get();
        if (principal == null || principal.getCustomerId() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage());
        }
        return principal.getCustomerId();
    }
}
