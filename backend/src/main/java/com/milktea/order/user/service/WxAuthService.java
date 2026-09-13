package com.milktea.order.user.service;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 微信小程序鉴权：code → openid（jscode2session）。
 * <p>
 * 依据排期 T05：后端以 appid+secret+code+grant_type 调
 * {@code https://api.weixin.qq.com/sns/jscode2session} 换取 openid。
 * appid/secret 全部走配置（{@code wx.appid}/{@code wx.secret}），不硬编码。
 * </p>
 */
@Slf4j
@Service
public class WxAuthService {

    private final RestClient wxClient;

    @Value("${wx.appid}")
    private String appid;

    @Value("${wx.secret}")
    private String secret;

    public WxAuthService(RestClient.Builder restClientBuilder) {
        this.wxClient = restClientBuilder.build();
    }

    /** 用登录 code 换取微信 openid；失败时抛出业务异常（PARAM_ERROR）。 */
    public String code2SessionOpenid(String code) {
        WxSessionResp resp = wxClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("api.weixin.qq.com")
                        .path("/sns/jscode2session")
                        .queryParam("appid", appid)
                        .queryParam("secret", secret)
                        .queryParam("js_code", code)
                        .queryParam("grant_type", "authorization_code")
                        .build())
                .retrieve()
                .body(WxSessionResp.class);

        if (resp == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "微信登录校验失败：空响应");
        }
        if (resp.getErrcode() != null && resp.getErrcode() != 0) {
            log.warn("[T05] jscode2session 返回错误 errcode={} errmsg={}", resp.getErrcode(), resp.getErrmsg());
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(),
                    "微信登录校验失败：" + (resp.getErrmsg() == null ? "errcode=" + resp.getErrcode() : resp.getErrmsg()));
        }
        if (resp.getOpenid() == null || resp.getOpenid().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "微信未返回 openid");
        }
        return resp.getOpenid();
    }

    /** 微信 jscode2session 响应（字段名与微信 JSON 原样对齐，避免命名策略依赖）。 */
    @Data
    public static class WxSessionResp {
        private String openid;
        private String session_key;
        private String unionid;
        private Integer errcode;
        private String errmsg;
    }
}
