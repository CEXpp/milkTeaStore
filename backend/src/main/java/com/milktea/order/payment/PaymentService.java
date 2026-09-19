package com.milktea.order.payment;

import com.milktea.order.common.exception.BusinessException;
import com.milktea.order.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 支付路由服务（LLD 5.1）：按配置项 {@code payment.active-channel} 选择激活的
 * {@link PaymentProvider} 实现——真店切微信时只改配置 + 新增实现，订单域调用零改动。
 *
 * <p>本服务只做渠道路由与派发，不感知订单表结构（域边界：订单状态与支付字段的写入
 * 由 order 域的 {@code OrderService} 在支付事务内完成）。</p>
 *
 * <p>启动即校验激活渠道有对应实现（fail fast）：配置写错/实现缺失时不带病上线。</p>
 */
@Slf4j
@Service
public class PaymentService {

    /** 渠道标识 → 实现。渠道名统一大写后建档，配置匹配不区分大小写。 */
    private final Map<String, PaymentProvider> providers;

    /** 激活渠道（原始配置值）。 */
    private final String activeChannel;

    public PaymentService(List<PaymentProvider> providers,
                          @Value("${payment.active-channel:mock}") String activeChannel) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                provider -> provider.channel().toUpperCase(Locale.ROOT), Function.identity()));
        this.activeChannel = activeChannel;
        PaymentProvider active = resolveProvider();
        log.info("[T12] 支付渠道已激活：{}（已注册实现：{}）", active.channel(), this.providers.keySet());
    }

    /**
     * 当前激活渠道名（写 {@code orders.pay_channel} 取值来源）。
     */
    public String activeChannel() {
        return resolveProvider().channel();
    }

    /**
     * 按配置路由并派发支付（策略模式：调用方不感知具体渠道）。
     */
    public PayResult pay(Long orderId, BigDecimal amount) {
        PaymentProvider provider = resolveProvider();
        log.info("[T12] 支付派发 channel={} orderId={} amount={}", provider.channel(), orderId, amount);
        return provider.pay(orderId, amount);
    }

    private PaymentProvider resolveProvider() {
        String key = activeChannel == null ? "" : activeChannel.trim().toUpperCase(Locale.ROOT);
        PaymentProvider provider = providers.get(key);
        if (provider == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR.getCode(),
                    "支付渠道未实现：" + activeChannel + "（可用：" + providers.keySet() + "）");
        }
        return provider;
    }
}
