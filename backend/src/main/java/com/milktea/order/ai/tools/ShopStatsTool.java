package com.milktea.order.ai.tools;

import com.milktea.order.common.result.PageResult;
import com.milktea.order.statistics.service.StatsService;
import com.milktea.order.statistics.vo.StatsOrderRowVo;
import com.milktea.order.statistics.vo.StatsRankingVo;
import com.milktea.order.statistics.vo.StatsSummaryVo;
import com.milktea.order.statistics.vo.StatsTrendVo;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 工具 · 只读经营统计（T66，W07/W08 前置）。
 *
 * <p><b>这个类里没有、也不允许有任何写操作</b>（任务卡设计纪律）：不上下架商品、
 * 不改价、不暂停接单、不修改订单状态。理由是「只读是 AI 进入后台的正确权限级别」——
 * 幻觉作用在只读分析上最坏只是「答得不准」，店长一眼可辨；作用在写操作上就是脏数据。</p>
 *
 * <p><b>口径不在这里定义</b>：四个方法全部委托 {@link StatsService}，也就是与商家端
 * 报表接口**同一份实现、同一套口径**（SRS 6.5，口径常量化在 {@code StatsMapper}）。
 * 这让「AI 说的数字与报表一致」成为结构上的必然，而不是靠提示词叮嘱模型别乱算。
 * 模型本身不做任何计算，只负责把这里返回的事实讲成话。</p>
 *
 * <p><b>只读由接口形状保证</b>：本类没有任何方法会改动数据库（连 {@code @Transactional} 都不需要）；
 * 越权提问（如「帮我把珍珠奶茶下架」）之所以被拒，根因是<b>根本没有这样的工具可调</b>，
 * 而非依赖模型听话。</p>
 */
@Component
@RequiredArgsConstructor
public class ShopStatsTool {

    private final StatsService statsService;

    /**
     * 当日经营概览。
     *
     * @param date 归属日 yyyy-MM-dd，可空（空 = 服务端今天）
     */
    @Tool("查询某一天的经营概览：营业额、订单数、杯数、退款额、各渠道分布。问「今天/昨天卖了多少钱」用这个。")
    public String dailySummary(@P(value = "日期 yyyy-MM-dd，留空表示今天", required = false) String date) {
        StatsSummaryVo summary = statsService.summary(date);
        StringBuilder text = new StringBuilder();
        text.append(summary.date()).append(" 经营概览：\n")
                .append("- 营业额（已扣作废退款）：").append(summary.totalAmount()).append(" 元\n")
                .append("- 订单数（含作废，不含超时关闭）：").append(summary.orderCount()).append(" 单\n")
                .append("- 杯数（主饮品件数）：").append(summary.cupCount()).append(" 杯\n")
                .append("- 退款额（作废单）：").append(summary.refundAmount()).append(" 元\n");
        if (summary.channel() == null || summary.channel().isEmpty()) {
            text.append("- 渠道分布：当日无渠道数据");
        } else {
            text.append("- 渠道分布：");
            for (int i = 0; i < summary.channel().size(); i++) {
                var channel = summary.channel().get(i);
                if (i > 0) {
                    text.append('；');
                }
                text.append(labelOf(channel.source())).append(' ')
                        .append(channel.orderCount()).append(" 单 / ")
                        .append(channel.amount()).append(" 元");
            }
        }
        return text.toString();
    }

    /**
     * 近 N 日趋势。
     *
     * @param days 天数（1..31），可空（空 = 7）
     */
    @Tool("查询近 N 日的逐日营业额与订单数趋势（N 默认 7，最大 31）。问「这几天怎么样」「是不是在涨」用这个。")
    public String trend(@P(value = "天数，默认 7", required = false) Integer days) {
        List<StatsTrendVo> trend = statsService.trend(days);
        StringBuilder text = new StringBuilder("近 ").append(trend.size()).append(" 日趋势（日期 / 订单数 / 营业额）：\n");
        for (StatsTrendVo point : trend) {
            text.append("- ").append(point.date()).append(" / ")
                    .append(point.orderCount()).append(" 单 / ")
                    .append(point.amount()).append(" 元\n");
        }
        return text.toString();
    }

    /**
     * 商品销量排行。
     *
     * @param range {@code today} 或 {@code 7d}，可空（空 = today）
     * @param top   取前 N 名（1..50），可空（空 = 10）
     */
    @Tool("查询商品销量排行（按杯数降序）。range 取 today 或 7d，top 取前几名。问「哪种卖得最好」用这个。")
    public String ranking(@P(value = "统计区间：today 或 7d", required = false) String range,
                          @P(value = "取前几名，默认 10", required = false) Integer top) {
        List<StatsRankingVo> rows = statsService.ranking(range, top);
        if (rows.isEmpty()) {
            return "该区间内没有销量数据。";
        }
        StringBuilder text = new StringBuilder("商品销量排行（商品 / 杯数 / 金额）：\n");
        int index = 1;
        for (StatsRankingVo row : rows) {
            text.append(index++).append(". ").append(row.productName())
                    .append(" / ").append(row.cupCount()).append(" 杯 / ")
                    .append(row.amount()).append(" 元\n");
        }
        return text.toString();
    }

    /**
     * 订单流水明细（含异常单）。
     *
     * @param date   归属日 yyyy-MM-dd，可空
     * @param status 状态过滤，可空
     * @param page   页码，从 1 起
     */
    @Tool("查询某天的订单流水明细（含待支付、超时关闭、作废等异常单）与作废原因。问「有哪些单出问题了」用这个。")
    public String orderFlow(@P(value = "日期 yyyy-MM-dd，留空表示今天", required = false) String date,
                            @P(value = "状态过滤：PENDING_PAYMENT/PAID/PREPARING/COMPLETED/CLOSED/VOIDED，留空为全部", required = false) String status,
                            @P(value = "页码，默认 1", required = false) Integer page) {
        PageResult<StatsOrderRowVo> result = statsService.orders(date, status, page == null ? 1 : page, 20);
        if (result.getList().isEmpty()) {
            return "该条件下没有订单流水。";
        }
        StringBuilder text = new StringBuilder("订单流水共 ").append(result.getTotal()).append(" 条，本页 ")
                .append(result.getList().size()).append(" 条：\n");
        for (StatsOrderRowVo row : result.getList()) {
            text.append("- ").append(row.orderNo())
                    .append(' ').append(labelOf(row.source()))
                    .append(' ').append(statusLabel(row.status()))
                    .append(" 金额 ").append(row.totalAmount()).append(" 元")
                    .append(" 下单 ").append(row.createdAt());
            if (row.voidReason() != null && !row.voidReason().isBlank()) {
                text.append(" 作废原因：").append(row.voidReason());
            }
            text.append('\n');
        }
        return text.toString();
    }

    private String labelOf(String source) {
        return switch (source == null ? "" : source) {
            case "MINI_PROGRAM" -> "小程序";
            case "AI" -> "AI";
            case "COUNTER" -> "柜台";
            default -> source == null ? "未知" : source;
        };
    }

    private String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "PENDING_PAYMENT" -> "待支付";
            case "PAID" -> "已支付";
            case "PREPARING" -> "制作中";
            case "COMPLETED" -> "已完成";
            case "CLOSED" -> "超时关闭";
            case "VOIDED" -> "已作废";
            default -> status == null ? "未知" : status;
        };
    }
}