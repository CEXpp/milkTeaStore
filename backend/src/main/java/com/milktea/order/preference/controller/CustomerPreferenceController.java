package com.milktea.order.preference.controller;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.common.result.R;
import com.milktea.order.preference.service.PreferenceService;
import com.milktea.order.preference.vo.PreferenceVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 口味档案接口（个人中心「口味档案」区块）。
 *
 * <p><b>这个 GET 带有副作用（惰性补算）</b>，是刻意为之：偏好表可能因长期未刷新而过期，
 * 而顾客下次看到自己的口味档案时应当看到最新的结论。把补算挂在读取上是成本最低的做法
 * （读时判断 {@code built_at} 是否超过 {@code preference.stale-hours}，未过期就是一次唯一索引查询），
 * 好过再加一个定时任务去遍历所有顾客。</p>
 *
 * <p>鉴权：路径 {@code /api/customer/**} 已在 {@code JwtAuthenticationFilter} 的顾客 JWT 矩阵内，
 * 无需改白名单；顾客身份一律取自 {@code AuthContext}，不从入参取（防越权看他人档案）。</p>
 */
@RestController
@RequestMapping("/api/customer/preference")
@RequiredArgsConstructor
public class CustomerPreferenceController {

    private final PreferenceService preferenceService;

    /** GET /api/customer/preference —— 读当前顾客的口味档案（必要时先惰性补算）。 */
    @GetMapping
    public R<PreferenceVo> getPreference() {
        AuthContext.Principal principal = AuthContext.get();
        if (principal == null || !principal.isCustomer()) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "仅顾客可查看口味档案");
        }
        return R.ok(preferenceService.view(principal.getCustomerId()));
    }
}
