package com.milktea.order.notify.controller;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.common.result.R;
import com.milktea.order.notify.dto.RemindRequest;
import com.milktea.order.notify.service.RemindTaskService;
import com.milktea.order.notify.vo.RemindVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 顾客「稍后提醒我再点」接口（T47 下单前预期管理，SRS 3.6 V1.1 补充）。
 *
 * <ul>
 *   <li>{@code POST /api/customer/remind}：登记一条稍后提醒（默认 15 分钟后推微信订阅消息）；</li>
 *   <li>{@code GET  /api/customer/remind}：查询当前待发提醒，供结算页展示「已登记」状态。</li>
 * </ul>
 *
 * <p><b>不产生订单</b>（任务卡验收项）：本接口只写 {@code remind_task}，绝不触达 {@code orders}
 * ——所谓「稍后提醒我再点」是提醒顾客回来重新下单，而不是替顾客挂一单。
 * 因此 3.5 下单流程与 6.1 超时关单规则完全不受影响，也不存在需要清理的「僵尸订单」。</p>
 *
 * <p><b>授权时机</b>：与 T44 一致——前端需在用户点击「稍后提醒」的<b>手势内</b>调用
 * {@code uni.requestSubscribeMessage} 取得 REMIND 模板授权，再调本接口登记；
 * 未授权也能登记成功（只是到点会因无额度而被记为 {@code SKIPPED}），不影响接口可用性。</p>
 */
@RestController
@RequestMapping("/api/customer/remind")
@RequiredArgsConstructor
public class CustomerRemindController {

    private final RemindTaskService remindTaskService;

    /**
     * 登记稍后提醒。
     *
     * @param request 延迟分钟数（可选；为空取服务端默认，超上限由服务端截断）
     */
    @PostMapping
    public R<RemindVo> schedule(@RequestBody(required = false) RemindRequest request) {
        Integer delay = request == null ? null : request.delayMinutes();
        return R.ok(remindTaskService.schedule(currentCustomerId(), delay));
    }

    /** 查询当前待发提醒；无待发提醒时 {@code data} 为 {@code null}。 */
    @GetMapping
    public R<RemindVo> pending() {
        return R.ok(remindTaskService.pending(currentCustomerId()));
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
