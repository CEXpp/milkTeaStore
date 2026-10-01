package com.milktea.order.order.controller;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.common.result.PageResult;
import com.milktea.order.common.result.R;
import com.milktea.order.order.dto.OrderCreateRequest;
import com.milktea.order.order.event.OrderEventPublisher;
import com.milktea.order.order.service.OrderQueryService;
import com.milktea.order.order.service.OrderService;
import com.milktea.order.order.vo.OrderCreateVo;
import com.milktea.order.order.vo.OrderDetailVo;
import com.milktea.order.order.vo.OrderListItemVo;
import com.milktea.order.order.vo.OrderStatusVo;
import com.milktea.order.order.vo.PayVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 顾客订单接口（/api/customer/orders/**，顾客 JWT，见 JwtAuthenticationFilter 路径矩阵）。
 *
 * <ul>
 *   <li>{@code POST /api/customer/orders}（T10）：下单，创建 PENDING_PAYMENT 订单；</li>
 *   <li>{@code POST /api/customer/orders/{id}/pay}（T12）：Mock 支付成功 → 分配取餐码 → 状态 PAID；</li>
 *   <li>{@code GET /api/customer/orders/active}（T13）：进行中订单列表（再扫码恢复）；</li>
 *   <li>{@code GET /api/customer/orders}（T13）：历史分页 {list,total,page,size}；</li>
 *   <li>{@code GET /api/customer/orders/{id}}（T13）：详情含订单项快照明细；</li>
 *   <li>{@code GET /api/customer/orders/{id}/status}（T13）：轮询轻量状态 {status,pickupCode,seq}；</li>
 *   <li>{@code GET /api/customer/orders/events?orderId=}（T43）：SSE 实时状态通道（轮询的增强通道，二者互为降级）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/customer/orders")
@RequiredArgsConstructor
public class CustomerOrderController {

    private final OrderService orderService;
    private final OrderQueryService orderQueryService;
    private final OrderEventPublisher orderEventPublisher;

    /**
     * 创建订单（LLD 3.2）：暂停接单 1006、计价 1001~1003 由服务层抛出，经全局异常处理器返回。
     */
    @PostMapping
    public R<OrderCreateVo> createOrder(@Valid @RequestBody OrderCreateRequest request) {
        return R.ok(orderService.createOrder(request));
    }

    /**
     * 发起支付（LLD 3.3）：归属校验 1005、状态冲突 1004 由服务层抛出，经全局异常处理器返回。
     */
    @PostMapping("/{id}/pay")
    public R<PayVo> pay(@PathVariable("id") Long id) {
        return R.ok(orderService.pay(id, currentCustomerId()));
    }

    /**
     * 进行中订单列表（LLD 3.3）：PENDING_PAYMENT / PAID / PREPARING，供再扫码恢复场景。
     */
    @GetMapping("/active")
    public R<List<OrderListItemVo>> activeOrders() {
        return R.ok(orderQueryService.listActive(currentCustomerId()));
    }

    /**
     * 历史订单分页（LLD 3.3）：全部状态，创建时间倒序；page 从 1 起，size 默认 20、最大 100。
     */
    @GetMapping
    public R<PageResult<OrderListItemVo>> historyOrders(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return R.ok(orderQueryService.listHistory(currentCustomerId(), page, size));
    }

    /**
     * 订单详情（LLD 3.3）：含完整订单项快照明细；他人订单 1005。
     */
    @GetMapping("/{id}")
    public R<OrderDetailVo> orderDetail(@PathVariable("id") Long id) {
        return R.ok(orderQueryService.detail(id, currentCustomerId()));
    }

    /**
     * 轮询轻量状态（LLD 3.3）：{status, pickupCode, seq}，3 秒间隔轮询专用。
     */
    @GetMapping("/{id}/status")
    public R<OrderStatusVo> orderStatus(@PathVariable("id") Long id) {
        return R.ok(orderQueryService.status(id, currentCustomerId()));
    }

    /**
     * 订单实时事件通道（T43，LLD 11.1）：{@code GET /api/customer/orders/events?orderId={id}}。
     *
     * <p>SSE 长连接（{@code text/event-stream}，响应体为 LLD 11.1 事件结构 JSON，<b>不套
     * 统一 R 响应体</b>——SSE 帧自带 {@code event:} 名与 {@code data:} 负载）。连接建立即补发一条
     * 当前状态快照（断线重连补齐状态），随后推送该单的状态迁移事件；SSE 不可用时前端回落
     * {@code /{id}/status} 3 秒轮询。</p>
     *
     * <p>归属校验复用 {@link OrderQueryService#status}：订单不存在 1004、非本人 1005
     * （订阅他人订单在建立连接前即被拒绝，不会泄露事件）。</p>
     */
    @GetMapping("/events")
    public SseEmitter orderEvents(@RequestParam("orderId") Long orderId) {
        Long customerId = currentCustomerId();
        OrderStatusVo snapshot = orderQueryService.status(orderId, customerId);
        return orderEventPublisher.subscribeCustomer(customerId, orderId, snapshot.status(), snapshot.pickupCode());
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
