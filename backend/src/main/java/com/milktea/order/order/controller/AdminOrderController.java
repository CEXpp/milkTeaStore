package com.milktea.order.order.controller;

import com.milktea.order.common.result.R;
import com.milktea.order.order.dto.OrderCreateRequest;
import com.milktea.order.order.dto.VoidRequest;
import com.milktea.order.order.service.AdminOrderService;
import com.milktea.order.order.service.OrderService;
import com.milktea.order.order.vo.AdminOrderSummaryVo;
import com.milktea.order.order.vo.BoardVo;
import com.milktea.order.order.vo.CounterOrderVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家订单接口（/api/admin/orders/**、/api/admin/counter-orders，商家 JWT，见 JwtAuthenticationFilter 路径矩阵）。
 *
 * <ul>
 *   <li>{@code GET /api/admin/orders/board}（T14）：看板全量轮询（3 秒）：pending/preparing 双分区 + 今日概览；</li>
 *   <li>{@code POST /api/admin/orders/{id}/start}（T14）：PAID → PREPARING（开始制作）；</li>
 *   <li>{@code POST /api/admin/orders/{id}/complete}（T14）：PREPARING → COMPLETED（出餐）；</li>
 *   <li>{@code POST /api/admin/orders/{id}/void}（T14）：PAID → VOIDED（作废，reason 必填）；</li>
 *   <li>{@code POST /api/admin/counter-orders}（T14/T20）：柜台人工点单，创建即 PAID + 分配取餐码。</li>
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

    /** 看板全量：双分区卡片（含等待/制作分钟数）+ 今日概览四数。 */
    @GetMapping("/orders/board")
    public R<BoardVo> board() {
        return R.ok(adminOrderService.board());
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
