package com.milktea.order.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信订阅消息配置（T44），绑定 {@code wx.subscribe.*}。
 *
 * <p>全部可缺省：<b>未填模板 ID 即视为未开通</b>（练手期 AppSecret 亦为空，
 * 见 {@code application-dev.yml} 的 {@code wx.secret}），此时下发链路静默短路，
 * 主流程与既有功能完全不受影响——这是 SRS 9.3「推送失败不影响全流程」的配置级保证。</p>
 *
 * <p><b>为什么模板参数是「key 名」而不是写死文案</b>：微信订阅消息的模板正文由商家在
 * 公众平台自行选用，其变量名为 {@code thing1 / character_string2 / time3} 这类
 * 「类型+序号」，无法由代码预知。故这里把变量名做成配置，取值由服务端按订单真值填充
 * （见 {@code WxSubscribeMessageService}），换模板只改配置、代码零改动。</p>
 *
 * @param enabled          总开关（缺省 false；模板 ID 与 AppSecret 齐备后置 true）
 * @param page             点击消息后的跳转页，限本小程序内页面，支持带参（默认进订单详情）
 * @param miniprogramState 跳转的小程序版本：developer / trial / formal（缺省 developer）
 * @param preparing        制作开始模板（PREPARING）
 * @param pickup           取餐提醒模板（COMPLETED，由 PICKUP_READY 事件触发）
 */
@ConfigurationProperties(prefix = "wx.subscribe")
public record WxSubscribeProperties(
        Boolean enabled,
        String page,
        String miniprogramState,
        Template preparing,
        Template pickup) {

    /** 业务模板键：制作开始。 */
    public static final String KEY_PREPARING = "PREPARING";

    /** 业务模板键：取餐提醒。 */
    public static final String KEY_PICKUP = "PICKUP";

    /** 默认跳转页（取餐码页，LLD 3.6 进行中订单页）。 */
    public static final String DEFAULT_PAGE = "pages/order-detail/index";

    /** 默认跳转版本（练手期为开发版；提审发布后由配置改为 formal）。 */
    public static final String DEFAULT_MINIPROGRAM_STATE = "developer";

    /**
     * 单个模板的变量映射。
     *
     * @param templateId    模板 ID（在公众平台「功能-订阅消息」中选用模板后获得）
     * @param statusKey     模板中承载「状态文案」的变量名，如 {@code thing1}；留空则不下发该字段
     * @param pickupCodeKey 承载「取餐码」的变量名，如 {@code character_string2}；留空则不下发
     * @param timeKey       承载「时间」的变量名，如 {@code time3}；留空则不下发
     */
    public record Template(String templateId, String statusKey, String pickupCodeKey, String timeKey) {

        /** 模板是否可用：模板 ID 非空是唯一硬条件（变量名可留空，表示该字段不下发）。 */
        public boolean usable() {
            return templateId != null && !templateId.isBlank();
        }
    }

    /** 缺省值归一：开关缺省为关，跳转参数缺省取默认值，模板缺省为空模板（不可用）。 */
    public WxSubscribeProperties {
        enabled = Boolean.TRUE.equals(enabled);
        page = (page == null || page.isBlank()) ? DEFAULT_PAGE : page.trim();
        miniprogramState = (miniprogramState == null || miniprogramState.isBlank())
                ? DEFAULT_MINIPROGRAM_STATE : miniprogramState.trim();
        preparing = (preparing == null) ? new Template(null, null, null, null) : preparing;
        pickup = (pickup == null) ? new Template(null, null, null, null) : pickup;
    }

    /** 总开关是否打开。 */
    public boolean active() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * 按业务键取模板；未知键返回 {@code null}。
     *
     * @param templateKey {@link #KEY_PREPARING} / {@link #KEY_PICKUP}
     */
    public Template templateFor(String templateKey) {
        if (KEY_PREPARING.equals(templateKey)) {
            return preparing;
        }
        if (KEY_PICKUP.equals(templateKey)) {
            return pickup;
        }
        return null;
    }

    /** 业务键是否合法（供订阅登记接口校验，避免脏数据落库）。 */
    public static boolean isKnownKey(String templateKey) {
        return KEY_PREPARING.equals(templateKey) || KEY_PICKUP.equals(templateKey);
    }
}
