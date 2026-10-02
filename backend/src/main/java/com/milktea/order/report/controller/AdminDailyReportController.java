package com.milktea.order.report.controller;

import com.milktea.order.common.result.R;
import com.milktea.order.report.service.DailyReportService;
import com.milktea.order.report.vo.DailyReportVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每日经营日报接口（T68，D5a · W08，商家 JWT）。
 *
 * <ul>
 *   <li>{@code GET  /api/admin/daily-report?date=}：读某日日报（首页卡片）；</li>
 *   <li>{@code POST /api/admin/daily-report/generate?date=}：手动生成 / 重跑某日日报。</li>
 * </ul>
 *
 * <p><b>手动生成接口存在的理由</b>：定时任务只在打烊时刻生效，演示与联调不可能等到 22 点；
 * 且重跑天然幂等（同一日覆盖同一行），故开放手动入口是安全的。
 * 它不是「让 AI 现算一份」，而是<b>走与定时任务完全相同的那条链路</b>。</p>
 */
@RestController
@RequestMapping("/api/admin/daily-report")
@RequiredArgsConstructor
public class AdminDailyReportController {

    private final DailyReportService dailyReportService;

    /**
     * 读某日日报。
     *
     * <p>未生成过时 {@code data} 为 {@code null}（不是空对象）——前端据此提示
     * 「今日日报将在打烊后生成」，而不会把一份现场算的东西当成「已生成的日报」。</p>
     */
    @GetMapping
    public R<DailyReportVo> find(@RequestParam(value = "date", required = false) String date) {
        return R.ok(dailyReportService.find(date));
    }

    /** 手动生成 / 重跑（与定时任务同链路，幂等）。 */
    @PostMapping("/generate")
    public R<DailyReportVo> generate(@RequestParam(value = "date", required = false) String date) {
        return R.ok(dailyReportService.generate(date));
    }
}