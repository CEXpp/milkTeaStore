package com.milktea.order.queue.controller;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.common.result.R;
import com.milktea.order.queue.service.QueueEstimateService;
import com.milktea.order.queue.vo.QueueEstimateVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 顾客端队列预估接口（T46，LLD 11.6）：{@code GET /api/customer/queue/estimate[?orderId=]}。
 *
 * <ul>
 *   <li><b>不带 orderId</b>（T47 结算页「下单前预期管理」）：当前全店队列的下单前预估，
 *       {@code orderId} / {@code position} 为 {@code null}；</li>
 *   <li><b>带 orderId</b>（T49 进行中订单页）：本单队列序号与等待预估，订单归属校验
 *       1004（不存在）/ 1005（非本人）。</li>
 * </ul>
 *
 * <p>两场景共用同一响应形态（{@link QueueEstimateVo}），前端无需分支解析。</p>
 */
@RestController
@RequestMapping("/api/customer/queue")
@RequiredArgsConstructor
public class CustomerQueueController {

    private final QueueEstimateService queueEstimateService;

    /**
     * 队列预估：带 orderId 取本单预估（含归属校验），不带取全店下单前预估。
     *
     * @param orderId 订单主键，可选
     */
    @GetMapping("/estimate")
    public R<QueueEstimateVo> estimate(@RequestParam(value = "orderId", required = false) Long orderId) {
        if (orderId == null) {
            return R.ok(queueEstimateService.estimateForNewOrder());
        }
        return R.ok(queueEstimateService.estimateForOrder(orderId, currentCustomerId()));
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
