package com.milktea.order.notify.controller;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.common.result.R;
import com.milktea.order.notify.config.WxSubscribeProperties;
import com.milktea.order.notify.dto.SubscribeReportRequest;
import com.milktea.order.notify.mapper.WxSubscribeQuotaMapper;
import com.milktea.order.notify.vo.SubscribeReportVo;
import com.milktea.order.notify.vo.SubscribeTemplateVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 顾客订阅消息接口组（T44，SRS 第 8 章「顾客·订阅」）。
 *
 * <ul>
 *   <li>{@code GET  /api/customer/subscribe/templates} —— 取可订阅模板 ID，供小程序拼
 *       {@code uni.requestSubscribeMessage} 的 {@code tmplIds}；未配置模板时返回空数组；</li>
 *   <li>{@code POST /api/customer/subscribe} —— 上报授权结果，为同意的模板累加可下发次数。</li>
 * </ul>
 *
 * <p><b>授权时机</b>（SRS 3.1 授权例外「不弹任何授权窗口」+ 任务卡「不得冷启动弹窗」）：
 * 由前端在<b>支付按钮点击的用户手势</b>内发起（微信基础库 2.8.2 起明确要求
 * 「用户发生点击行为或者发起支付回调后，才可以调起订阅消息界面」），本接口只承担
 * 「登记结果」的职责，不主动发起任何授权。</p>
 *
 * <p><b>拒绝也不报错</b>：用户拒绝时前端不上报（{@code accepted} 为空），本接口正常返回
 * {@code granted=0}——验收项「拒绝授权不影响全流程」在此体现为「没有分支、没有异常」。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/customer/subscribe")
@RequiredArgsConstructor
public class CustomerSubscribeController {

    private final WxSubscribeProperties properties;
    private final WxSubscribeQuotaMapper quotaMapper;

    /**
     * 可订阅模板列表。
     *
     * @return 仅包含「已启用 + 模板 ID 已配置」的模板；均未配置时为空数组（前端据此跳过授权）
     */
    @GetMapping("/templates")
    public R<List<SubscribeTemplateVo>> templates() {
        List<SubscribeTemplateVo> list = new ArrayList<>(3);
        if (properties.active()) {
            collect(list, WxSubscribeProperties.KEY_PREPARING);
            collect(list, WxSubscribeProperties.KEY_PICKUP);
            // T47：结算页「稍后提醒我再点」需要在同一处拿到模板 ID（前端点击时才能同步发起授权）
            collect(list, WxSubscribeProperties.KEY_REMIND);
        }
        return R.ok(list);
    }

    /**
     * 上报授权结果：为每个用户同意的模板登记一次可下发额度。
     *
     * @param request 同意的业务模板键列表
     */
    @PostMapping
    public R<SubscribeReportVo> report(@RequestBody(required = false) SubscribeReportRequest request) {
        Long customerId = currentCustomerId();
        List<String> accepted = request == null ? null : request.accepted();
        int granted = 0;
        if (accepted != null) {
            LocalDateTime now = LocalDateTime.now();
            for (String key : accepted) {
                if (key == null || !WxSubscribeProperties.isKnownKey(key)) {
                    // 未知键忽略（不落脏数据，也不因前端版本差报错）
                    continue;
                }
                quotaMapper.grant(customerId, key, 1, now);
                granted++;
            }
        }
        int remaining = quotaMapper.remainingTotal(customerId);
        if (granted > 0) {
            log.info("[T44] 订阅授权已登记 customerId={} granted={} remainingTotal={}",
                    customerId, granted, remaining);
        }
        return R.ok(new SubscribeReportVo(granted, remaining));
    }

    private void collect(List<SubscribeTemplateVo> list, String key) {
        WxSubscribeProperties.Template template = properties.templateFor(key);
        if (template != null && template.usable()) {
            list.add(new SubscribeTemplateVo(key, template.templateId()));
        }
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
