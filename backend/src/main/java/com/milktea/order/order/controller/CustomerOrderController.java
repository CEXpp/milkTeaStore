package com.milktea.order.order.controller;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.common.result.PageResult;
import com.milktea.order.common.result.R;
import com.milktea.order.order.dto.OrderCreateRequest;
import com.milktea.order.order.dto.OrderEtaRequest;
import com.milktea.order.order.event.OrderEventPublisher;
import com.milktea.order.order.service.ArrivalService;
import com.milktea.order.order.service.OrderQueryService;
import com.milktea.order.order.service.OrderService;
import com.milktea.order.order.service.OrderTimelineService;
import com.milktea.order.order.vo.ArrivalEtaVo;
import com.milktea.order.order.vo.ArrivalVo;
import com.milktea.order.order.vo.OrderCreateVo;
import com.milktea.order.order.vo.OrderDetailVo;
import com.milktea.order.order.vo.OrderListItemVo;
import com.milktea.order.order.vo.OrderStatusVo;
import com.milktea.order.order.vo.OrderTimelineVo;
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
 *   <li>{@code GET /api/customer/orders/events?orderId=}（T43）：SSE 实时状态通道（轮询的增强通道，二者互为降级）；</li>
 *   <li>{@code POST /api/customer/orders/{id}/arrive}（T49）：申报「我已到店」，仅供看板提示，
 *       不改状态机、不改队列排序、不影响统计。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/customer/orders")
@RequiredArgsConstructor
public class CustomerOrderController {

    private final OrderService orderService;
    private final OrderQueryService orderQueryService;
    private final OrderEventPublisher orderEventPublisher;
    private final ArrivalService arrivalService;
    private final OrderTimelineService orderTimelineService;

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

    /**
     * 申报「我已到店」（T49 到店握手，W19）。
     *
     * <p>只写 {@code orders.arrived_at} 一个信号：看板据此在卡片上打「已到店」标记，
     * 但<b>不改变队列排序</b>（验收项「不强制改排序」）——店长可据此优先处理，也可以无视。</p>
     *
     * <p>幂等：重复申报返回首次申报时间；仅 PAID / PREPARING 可申报（1004），他人订单 1005。</p>
     */
    @PostMapping("/{id}/arrive")
    public R<ArrivalVo> arrive(@PathVariable("id") Long id) {
        return R.ok(arrivalService.arrive(id, currentCustomerId()));
    }

    /**
     * 申报 / 修改 / 撤销「我到店还需 X 分钟」（T51，W02「我将到」）。
     *
     * <p>看板据此给出<b>建议制作顺序</b>（到达近的优先）；不申报时与既往「先付先做」逐字一致。
     * 传 {@code etaMinutes=null} 即撤销。</p>
     */
    @PostMapping("/{id}/eta")
    public R<ArrivalEtaVo> updateEta(@PathVariable("id") Long id,
                                     @RequestBody(required = false) OrderEtaRequest request) {
        Integer etaMinutes = request == null ? null : request.etaMinutes();
        return R.ok(arrivalService.updateEta(id, currentCustomerId(), etaMinutes));
    }

    /**
     * 订单全生命周期时间轴（T50，W03）。
     *
     * <p>六时间戳 + 每段耗时 + 与「同渠道同日制作耗时中位数」的对比。纯只读：
     * 不新增任何数据（六时间戳 V1 已预留），也不改变任何状态。</p>
     */
    @GetMapping("/{id}/timeline")
    public R<OrderTimelineVo> timeline(@PathVariable("id") Long id) {
        return R.ok(orderTimelineService.timeline(id, currentCustomerId()));
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
