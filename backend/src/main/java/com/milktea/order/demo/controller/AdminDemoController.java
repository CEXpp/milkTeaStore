package com.milktea.order.demo.controller;

import com.milktea.order.common.result.R;
import com.milktea.order.demo.service.DemoSeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演示沙盘造数接口（T74，E8d · F11）。
 *
 * <p><b>「真店配置下端点不可达」是本类存在的形式</b>（验收项）：整类挂
 * {@code @ConditionalOnProperty}——配置里没有 {@code demo.seed.enabled=true} 时，
 * 这个 Bean <b>根本不会被注册</b>，URL 返回 404。</p>
 *
 * <p>刻意<b>不</b>做成「注册了但进去报 403 / 1006」：那意味着攻击面还在，
 * 只是多加了道门；真店环境里「这个 URL 不存在」比「这个 URL 拒绝你」更可靠，
 * 也省掉一份「哪些接口在什么配置下开着」的维护清单。</p>
 *
 * <p>开关沿用 9.2「Mock 支付仅练手配置启用」的同一模式：一个配置项控制一个能力，
 * 真店期把 {@code demo.seed.enabled} 置为 false 即可，与业务代码零耦合。</p>
 */
@RestController
@RequestMapping("/api/admin/demo")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "demo.seed.enabled", havingValue = "true")
public class AdminDemoController {

    private final DemoSeedService demoSeedService;

    /**
     * 造当日演示数据（body/query 的 {@code orders} = 常规单笔数，默认 12）。
     *
     * <p>跨三渠道（MINI_PROGRAM / AI / COUNTER）轮流分配，另固定追加
     * 1 笔作废与 1 笔超时关闭，使 AC-15 的「营业额 = 有效实付 − 作废退款」
     * 与 AC-05 的超时关闭当场可验。</p>
     */
    @PostMapping("/seed-day")
    public R<DemoSeedService.SeedResult> seedDay(
            @RequestParam(value = "orders", defaultValue = "12") int orders) {
        return R.ok(demoSeedService.seedDay(orders));
    }

    /**
     * 清理当日演示数据。
     *
     * <p>只删演示形态的单（无顾客身份 / 支付渠道为 MOCK·COUNTER），
     * <b>不按日期无脑删</b>——演示当天也可能真有顾客下单。</p>
     */
    @DeleteMapping("/seed-day")
    public R<Integer> cleanup() {
        return R.ok(demoSeedService.cleanup());
    }
}