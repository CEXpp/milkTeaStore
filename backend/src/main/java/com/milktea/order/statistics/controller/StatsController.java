package com.milktea.order.statistics.controller;

import com.milktea.order.common.result.PageResult;
import com.milktea.order.common.result.R;
import com.milktea.order.statistics.service.StatsService;
import com.milktea.order.statistics.vo.StatsOrderRowVo;
import com.milktea.order.statistics.vo.StatsRankingVo;
import com.milktea.order.statistics.vo.StatsSummaryVo;
import com.milktea.order.statistics.vo.StatsTrendVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端账台统计（T34，LLD 3.5.4，商家 JWT——路径属 {@code /api/admin/**} 鉴权矩阵）。
 *
 * <ul>
 *   <li>{@code GET /api/admin/stats/summary?date=yyyy-MM-dd}：当日概览（营业额 / 订单数 / 杯数 / 退款额 / 渠道分布）；</li>
 *   <li>{@code GET /api/admin/stats/trend?days=7}：近 N 日逐日营业额与订单数；</li>
 *   <li>{@code GET /api/admin/stats/ranking?range=today|7d&top=10}：商品销量排行；</li>
 *   <li>{@code GET /api/admin/stats/orders?date=&status=&page=&size=}：按日订单流水明细（分页，含异常单）。</li>
 * </ul>
 *
 * <p>统计口径（SRS 6.5）与聚合 SQL 统一收敛在 {@code StatsMapper}，本控制器只做入参透传与响应包装。</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    /** 当日概览：date 缺省为服务端今天（按 app.time-zone）；金额均为两位小数字符串。 */
    @GetMapping("/stats/summary")
    public R<StatsSummaryVo> summary(@RequestParam(value = "date", required = false) String date) {
        return R.ok(statsService.summary(date));
    }

    /** 近 N 日趋势：days 缺省 7，日期轴连续（无单日补 0）。 */
    @GetMapping("/stats/trend")
    public R<List<StatsTrendVo>> trend(@RequestParam(value = "days", required = false) Integer days) {
        return R.ok(statsService.trend(days));
    }

    /** 商品销量排行：range 取 today / 7d（缺省 today），top 缺省 10。 */
    @GetMapping("/stats/ranking")
    public R<List<StatsRankingVo>> ranking(
            @RequestParam(value = "range", required = false) String range,
            @RequestParam(value = "top", required = false) Integer top) {
        return R.ok(statsService.ranking(range, top));
    }

    /** 按日订单流水明细：含待支付 / 超时关闭 / 作废等异常单，供对账。 */
    @GetMapping("/stats/orders")
    public R<PageResult<StatsOrderRowVo>> orders(
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return R.ok(statsService.orders(date, status, page, size));
    }
}
