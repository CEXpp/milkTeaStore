package com.milktea.order.notify.service;

import com.milktea.order.notify.config.WxSubscribeProperties;
import com.milktea.order.notify.mapper.WxSubscribeQuotaMapper;
import com.milktea.order.order.entity.Order;
import com.milktea.order.order.event.OrderEvent;
import com.milktea.order.order.event.OrderEventListener;
import com.milktea.order.order.event.OrderEventType;
import com.milktea.order.order.mapper.OrderMapper;
import com.milktea.order.user.entity.Customer;
import com.milktea.order.user.mapper.CustomerMapper;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 微信订阅消息下发（T44，SRS 第 8 章「顾客·订阅」接口组 / LLD 11.4 双信道取餐提醒）。
 *
 * <h2>触发口径</h2>
 * <ul>
 *   <li>{@code ORDER_STATUS_CHANGED} 且状态为 {@code PREPARING} → 制作开始模板；</li>
 *   <li>{@code PICKUP_READY} → 取餐提醒模板。</li>
 * </ul>
 * <p>出餐时发布器会先发 {@code ORDER_STATUS_CHANGED(COMPLETED)} 再发 {@code PICKUP_READY}
 * （LLD 11.1），此处<b>只认 PICKUP_READY</b>，避免同一动作推两条——即任务卡验收项
 * 「同一次订阅不重复推送」。</p>
 *
 * <h2>为什么异步</h2>
 * <p>下发是外部 HTTP 调用，而它发生在订单事务的 {@code afterCommit} 回调里（业务线程）。
 * 同步下发会把微信的网络抖动直接传导成「商家点『出餐』后转圈」，违背 SRS 9.3
 * 「推送失败不影响主链路」。故提交到<b>单线程</b>执行器：既立即返回，又天然串行化，
 * 顺带规避微信 {@code errcode=43108}（并发下发消息给同一个粉丝）。</p>
 *
 * <p><b>线程与身份</b>：本执行器只承载推送，不读取 {@code AuthContext}——事件体已带
 * {@code orderId}，openid 由本服务查库获得，因此不涉及 {@code ScopedValue} 的跨线程传播
 * （与项目「跨线程须配合 StructuredTaskScope」的约定并不冲突：该约定针对身份上下文传递）。</p>
 *
 * <h2>降级</h2>
 * <p>未开总开关 / 未配模板 ID / 未配 AppSecret → 静默跳过；无额度（用户拒绝授权或已用完）→
 * 静默跳过；下发失败 → 记日志并退还额度。任何一步都不抛出异常（SRS 9.3）。</p>
 */
@Slf4j
@Service
public class WxSubscribeMessageService implements OrderEventListener {

    /** 下发接口路径（微信官方：POST /cgi-bin/message/subscribe/send?access_token=）。 */
    private static final String SEND_PATH = "/cgi-bin/message/subscribe/send";

    /** 语言（微信契约默认 zh_CN）。 */
    private static final String LANG_ZH_CN = "zh_CN";

    /** 状态文案：制作中。 */
    private static final String TEXT_PREPARING = "制作中";

    /** 状态文案：请取餐。 */
    private static final String TEXT_PICKUP = "请取餐";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final WxSubscribeProperties properties;
    private final WxAccessTokenService accessTokenService;
    private final WxSubscribeQuotaMapper quotaMapper;
    private final OrderMapper orderMapper;
    private final CustomerMapper customerMapper;
    private final RestClient wxRestClient;

    /** 单线程推送执行器：串行化下发，避免微信 43108（并发下发同一粉丝）。 */
    private final ExecutorService pushExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "wx-subscribe-push");
        thread.setDaemon(true);
        return thread;
    });

    public WxSubscribeMessageService(WxSubscribeProperties properties,
                                     WxAccessTokenService accessTokenService,
                                     WxSubscribeQuotaMapper quotaMapper,
                                     OrderMapper orderMapper,
                                     CustomerMapper customerMapper,
                                     @Qualifier("wxRestClient") RestClient wxRestClient) {
        this.properties = properties;
        this.accessTokenService = accessTokenService;
        this.quotaMapper = quotaMapper;
        this.orderMapper = orderMapper;
        this.customerMapper = customerMapper;
        this.wxRestClient = wxRestClient;
    }

    @Override
    public void onOrderEvent(OrderEvent event) {
        String templateKey = resolveTemplateKey(event);
        if (templateKey == null) {
            return;
        }
        // 配置级降级：未启用 / 无模板 ID，直接不排队
        if (!properties.active()) {
            log.debug("[T44] 订阅消息未启用（wx.subscribe.enabled=false），跳过 event={} orderId={}",
                    event.type(), event.orderId());
            return;
        }
        WxSubscribeProperties.Template template = properties.templateFor(templateKey);
        if (template == null || !template.usable()) {
            log.debug("[T44] 模板未配置，跳过下发 templateKey={} orderId={}", templateKey, event.orderId());
            return;
        }
        pushExecutor.execute(() -> pushSafely(templateKey, template, event));
    }

    /** 事件 → 业务模板键；无关事件返回 {@code null}（不排队）。 */
    private String resolveTemplateKey(OrderEvent event) {
        if (OrderEventType.PICKUP_READY.name().equals(event.type())) {
            return WxSubscribeProperties.KEY_PICKUP;
        }
        if (OrderEventType.ORDER_STATUS_CHANGED.name().equals(event.type())
                && "PREPARING".equals(event.status())) {
            return WxSubscribeProperties.KEY_PREPARING;
        }
        return null;
    }

    /** 推送外层兜底：任何异常都不允许逃逸到执行器线程之外（SRS 9.3）。 */
    private void pushSafely(String templateKey, WxSubscribeProperties.Template template, OrderEvent event) {
        Long orderId = event.orderId();
        try {
            push(templateKey, template, event);
        } catch (Exception e) {
            log.warn("[T44] 订阅消息下发异常（已忽略，不影响主链路）templateKey={} orderId={}：{}",
                    templateKey, orderId, e.getMessage());
        }
    }

    private void push(String templateKey, WxSubscribeProperties.Template template, OrderEvent event) {
        Order order = orderMapper.selectById(event.orderId());
        if (order == null) {
            log.warn("[T44] 订单不存在，跳过订阅消息 orderId={}", event.orderId());
            return;
        }
        if (order.getCustomerId() == null) {
            // 柜台单无归属顾客，无 openid 可推
            return;
        }
        Map<String, Object> data = buildData(template, templateKey, order);
        if (data.isEmpty()) {
            log.warn("[T44] 模板变量未配置任何取值字段，跳过下发 templateKey={}（微信会返回 47003 参数错误）", templateKey);
            return;
        }
        deliver(order.getCustomerId(), templateKey, data, pageFor(order.getId()), "orderId=" + order.getId());
    }

    /**
     * 通用下发出口（T44 订阅消息的唯一落地路径）。
     *
     * <p>T47「稍后提醒我再点」不是订单事件驱动的（它发生在下单之前、没有订单），
     * 因此不能走 {@link #onOrderEvent}；但它需要的「占额度 → 取 token → POST → 失败退额度」
     * 与订单推送完全一致，故抽成本方法复用，避免两套下发逻辑各自演化导致口径分叉。</p>
     *
     * @param customerId  目标顾客
     * @param templateKey 业务模板键
     * @param data        模板数据（变量名 → {value}）
     * @param page        点击跳转页
     * @param trace       日志上下文（如 {@code "orderId=3001"} / {@code "remindTaskId=7"}）
     * @return {@code true} = 微信已受理（errcode=0）；{@code false} = 未下发（未启用 / 无模板 /
     *         无额度 / 网络或微信拒绝）——一律不抛异常
     */
    public boolean deliver(Long customerId, String templateKey, Map<String, Object> data,
                           String page, String trace) {
        if (!properties.active()) {
            log.debug("[T44] 订阅消息未启用，跳过下发 templateKey={} {}", templateKey, trace);
            return false;
        }
        WxSubscribeProperties.Template template = properties.templateFor(templateKey);
        if (template == null || !template.usable()) {
            log.debug("[T44] 模板未配置，跳过下发 templateKey={} {}", templateKey, trace);
            return false;
        }
        if (data == null || data.isEmpty()) {
            log.warn("[T44] 模板变量为空，跳过下发 templateKey={}（微信会返回 47003 参数错误）{}", templateKey, trace);
            return false;
        }
        Customer customer = customerMapper.selectById(customerId);
        if (customer == null || !StringUtils.hasText(customer.getOpenid())) {
            log.warn("[T44] 顾客或 openid 缺失，跳过下发 customerId={} {}", customerId, trace);
            return false;
        }

        // 原子占用额度：0 表示用户未授权或次数已用完（微信侧即 43101 的成因），静默跳过
        if (quotaMapper.consume(customerId, templateKey, LocalDateTime.now()) == 0) {
            log.info("[T44] 无可用订阅额度，跳过下发（用户未授权或已用完）customerId={} templateKey={} {}",
                    customerId, templateKey, trace);
            return false;
        }

        String accessToken = accessTokenService.get();
        if (!StringUtils.hasText(accessToken)) {
            quotaMapper.refund(customerId, templateKey, LocalDateTime.now());
            log.warn("[T44] access_token 不可用，已退还额度 templateKey={} {}", templateKey, trace);
            return false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", customer.getOpenid());
        body.put("template_id", template.templateId());
        body.put("page", page);
        body.put("miniprogram_state", properties.miniprogramState());
        body.put("lang", LANG_ZH_CN);
        body.put("data", data);

        SendResp resp;
        try {
            resp = wxRestClient.post()
                    .uri(uriBuilder -> uriBuilder.path(SEND_PATH).queryParam("access_token", accessToken).build())
                    .body(body)
                    .retrieve()
                    .body(SendResp.class);
        } catch (Exception e) {
            quotaMapper.refund(customerId, templateKey, LocalDateTime.now());
            log.warn("[T44] 订阅消息下发请求失败，已退还额度 templateKey={} {}：{}", templateKey, trace, e.getMessage());
            return false;
        }

        Integer errcode = resp == null ? null : resp.getErrcode();
        if (errcode != null && errcode != 0) {
            quotaMapper.refund(customerId, templateKey, LocalDateTime.now());
            log.warn("[T44] 订阅消息下发被微信拒绝，已退还额度 errcode={} errmsg={} templateKey={} {}",
                    errcode, resp.getErrmsg(), templateKey, trace);
            return false;
        }
        log.info("[T44] 订阅消息已下发 templateKey={} customerId={} {}", templateKey, customerId, trace);
        return true;
    }

    /**
     * 组装模板数据：只下发配置了变量名的字段（微信模板正文由商家自选，变量名不可预知）。
     *
     * <p>取值一律来自订单真值，与 SSE / 轮询出口同源（LLD 11.4「两信道内容同源」）。</p>
     */
    private Map<String, Object> buildData(WxSubscribeProperties.Template template,
                                          String templateKey,
                                          Order order) {
        Map<String, Object> data = new LinkedHashMap<>();
        String statusText = WxSubscribeProperties.KEY_PICKUP.equals(templateKey) ? TEXT_PICKUP : TEXT_PREPARING;
        putIfConfigured(data, template.statusKey(), statusText);
        putIfConfigured(data, template.pickupCodeKey(), order.getPickupCode());
        putIfConfigured(data, template.timeKey(), LocalDateTime.now().format(TIME_FMT));
        return data;
    }

    private void putIfConfigured(Map<String, Object> data, String keyword, String value) {
        if (StringUtils.hasText(keyword) && StringUtils.hasText(value)) {
            data.put(keyword.trim(), Map.of("value", value));
        }
    }

    /** 点击消息的跳转页：配置页 + 订单 id（LLD 3.6 进行中订单页）。 */
    private String pageFor(Long orderId) {
        return properties.page() + "?id=" + orderId;
    }

    @PreDestroy
    public void shutdown() {
        pushExecutor.shutdown();
    }

    /**
     * 下发接口响应（微信契约：{@code {errcode, errmsg}}，0 表示成功）。
     *
     * <p>用 JavaBean 而非 record：与 {@code WxAuthService} 的两个响应体风格保持一致，
     * 也便于后续按需读取更多微信返回字段。</p>
     */
    @Data
    public static class SendResp {
        private Integer errcode;
        private String errmsg;
    }
}
