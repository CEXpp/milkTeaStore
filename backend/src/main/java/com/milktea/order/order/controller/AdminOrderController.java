package com.milktea.order.order.controller;

import com.milktea.order.common.result.R;
import com.milktea.order.order.dto.OrderCreateRequest;
import com.milktea.order.order.dto.VoidRequest;
import com.milktea.order.order.event.OrderEventPublisher;
import com.milktea.order.order.service.AdminOrderService;
import com.milktea.order.order.service.OrderService;
import com.milktea.order.order.vo.AdminOrderSummaryVo;
import com.milktea.order.order.vo.BoardVo;
import com.milktea.order.order.vo.CounterOrderVo;
import com.milktea.order.order.vo.OrderChecklistVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 商家订单接口（/api/admin/orders/**、/api/admin/counter-orders，商家 JWT，见 JwtAuthenticationFilter 路径矩阵）。
 *
 * <ul>
 *   <li>{@code GET /api/admin/orders/board}（T14）：看板全量轮询（3 秒）：pending/preparing 双分区 + 今日概览；</li>
 *   <li>{@code POST /api/admin/orders/{id}/start}（T14）：PAID → PREPARING（开始制作）；</li>
 *   <li>{@code POST /api/admin/orders/{id}/complete}（T14）：PREPARING → COMPLETED（出餐）；</li>
 *   <li>{@code POST /api/admin/orders/{id}/void}（T14）：PAID → VOIDED（作废，reason 必填）；</li>
 *   <li>{@code POST /api/admin/counter-orders}（T14/T20）：柜台人工点单，创建即 PAID + 分配取餐码。</li>
 *   <li>{@code GET /api/admin/board/events}（T43）：看板 SSE 实时事件通道（3 秒全量轮询的增强通道）。</li>
 * </ul>
 *
 * <p>状态冲突（1004）与参数错误（1001）由服务层/校验层抛出，经全局异常处理器返回。</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminOrderController {

    private final AdminOrderService adminOrderService;
    private final OrderService orderService;
    private final OrderEventPublisher orderEventPublisher;

    /** 看板全量：双分区卡片（含等待/制作分钟数）+ 今日概览四数。 */
    @GetMapping("/orders/board")
    public R<BoardVo> board() {
        return R.ok(adminOrderService.board());
    }

    /**
     * 出餐核对清单（T58，W20）：该单的规格快照明细（杯型 / 温度 / 甜度 / 加料）与备注。
     *
     * <p><b>纯只读</b>：出餐仍按 6.1 原规则走状态机，勾选确认只是前端的防错交互，
     * <b>不是</b>状态迁移的前置条件（任务卡「设计纪律」）。</p>
     */
    @GetMapping("/orders/{id}/checklist")
    public R<OrderChecklistVo> checklist(@PathVariable("id") Long id) {
        return R.ok(adminOrderService.checklist(id));
    }

    /**
     * 看板实时事件通道（T43，LLD 11.1）：{@code GET /api/admin/board/events}。
     *
     * <p>SSE 长连接，推送全店订单状态迁移事件（{@code data} 为 LLD 11.1 事件结构 JSON，
     * 不套统一 R 响应体）。前端每收到事件刷新一次 {@code /orders/board} 以复用既有的
     * 新单差集判定；SSE 不可用时回落 3 秒全量轮询（LLD 11.1 降级策略）。</p>
     */
    @GetMapping("/board/events")
    public SseEmitter boardEvents() {
        return orderEventPublisher.subscribeAdmin();
    }

    /** 开始制作：PAID → PREPARING，重复调用/越界返回 1004。 */
    @PostMapping("/orders/{id}/start")
    public R<AdminOrderSummaryVo> start(@PathVariable("id") Long id) {
        return R.ok(adminOrderService.start(id));
    }

    /** 出餐完成：PREPARING → COMPLETED。 */
    @PostMapping("/orders/{id}/complete")
    public R<AdminOrderSummaryVo> complete(@PathVariable("id") Long id) {
        return R.ok(adminOrderService.complete(id));
    }

    /** 作废（仅限 PAID，未开始制作）：reason 必填，退款额进统计。 */
    @PostMapping("/orders/{id}/void")
    public R<AdminOrderSummaryVo> voidOrder(@PathVariable("id") Long id,
                                            @Valid @RequestBody VoidRequest request) {
        return R.ok(adminOrderService.voidOrder(id, request.getReason()));
    }

    /** 柜台人工点单：结构同顾客下单 items，创建即直接 PAID，分配取餐码，customer_id 为 NULL。 */
    @PostMapping("/counter-orders")
    public R<CounterOrderVo> counterOrder(@Valid @RequestBody OrderCreateRequest request) {
        return R.ok(orderService.createCounterOrder(request));
    }
}
