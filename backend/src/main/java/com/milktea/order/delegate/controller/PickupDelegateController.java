package com.milktea.order.delegate.controller;

import com.milktea.order.common.jwt.AuthContext;
import com.milktea.order.common.result.R;
import com.milktea.order.delegate.service.PickupDelegateService;
import com.milktea.order.delegate.vo.DelegatePickupVo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 取餐凭证转赠接口（T65，C4e · W15）。
 *
 * <ul>
 *   <li>{@code POST /api/customer/orders/{id}/delegate}：原主签发限时一次性委托令牌；</li>
 *   <li>{@code POST /api/customer/orders/{id}/delegate/revoke}：原主随时收回；</li>
 *   <li>{@code GET  /api/customer/orders/delegate/{token}}：<b>公开</b>——代取人凭令牌取凭证。</li>
 * </ul>
 *
 * <p><b>签发与撤销需顾客 JWT</b>（在 {@code /api/customer/**} 矩阵内），
 * 但<b>取凭证的接口不加白名单里的鉴权豁免也不需要顾客身份</b>——它靠令牌本身授权。
 * 代取人可能根本没登录过小程序，让他先登录再取一杯奶茶是本末倒置。</p>
 *
 * <p>取凭证接口已在 {@code JwtAuthenticationFilter} 的白名单中放行（见
 * {@code EXACT_WHITELIST} 的说明）：路径属 {@code /api/customer/} 但<b>不要求</b>顾客身份，
 * 由 {@code PickupTokenMapper.consume} 的四条条件完成授权。</p>
 */
@RestController
@RequestMapping("/api/customer/orders")
@RequiredArgsConstructor
public class PickupDelegateController {

    private final PickupDelegateService delegateService;

    /**
     * 签发委托令牌：body {@code {minutes, proxyTag}}，minutes 可省（默认 120）。
     *
     * <p>返回的令牌<b>只在此刻给一次</b>，系统内不留可再次查询的明文副本——
     * 页面必须提示用户自己保存（截图发给同事）。</p>
     */
    @PostMapping("/{id}/delegate")
    public R<DelegateTokenVo> issue(@PathVariable("id") Long id,
                                    @RequestBody(required = false) DelegateIssueRequest request) {
        Integer minutes = request == null ? null : request.getMinutes();
        String proxyTag = request == null ? null : request.getProxyTag();
        String token = delegateService.issue(id, AuthContext.get().getCustomerId(), minutes, proxyTag);
        return R.ok(new DelegateTokenVo(token));
    }

    /** 原主收回：撤销后令牌立即失效，代取人再打开会被拒。 */
    @PostMapping("/{id}/delegate/revoke")
    public R<Void> revoke(@PathVariable("id") Long id) {
        delegateService.revoke(id, AuthContext.get().getCustomerId());
        return R.ok(null);
    }

    /**
     * 代取人凭令牌取凭证（<b>打开即核销</b>，一次性）。
     *
     * <p>刻意<b>用 GET 而非 POST</b>：代取人往往是从聊天窗口点链接进来的，
     * 微信内 GET 天然可被直接打开，POST 需要额外的表单或 JS 转发。
     * 代价是令牌会出现在浏览器/网关日志里——因此令牌是<b>32 字节随机</b>且
     * <b>核销即失效</b>，日志泄露的窗口只有一个请求。</p>
     *
     * @param token   令牌
     * @param proxyTag 代取人标识（可选，问询「你是谁」便于店员核对）
     */
    @GetMapping("/delegate/{token}")
    public R<DelegatePickupVo> redeem(@PathVariable("token") String token,
                                      @RequestParam(value = "proxyTag", required = false) String proxyTag) {
        return R.ok(delegateService.redeem(token, proxyTag));
    }

    /** 签发响应：只给令牌本体。 */
    public record DelegateTokenVo(String token) {
    }

    /** 签发请求：有效期与代取人标识均可选填。 */
    @Data
    public static class DelegateIssueRequest {
        /** 有效期（分钟），空则 120，上限 720 */
        private Integer minutes;
        /** 代取人标识（选填，如「李工」），仅供店员识别来者 */
        private String proxyTag;
    }
}