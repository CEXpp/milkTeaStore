package com.milktea.order.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日经营日报（daily_report，T68 · D5a）。
 *
 * <p><b>为什么落库而不是每次现算</b>（见 V8 迁移头注释）：日报是「打烊后生成的一次快照」，
 * 第二天再看昨天的日报应当看到昨天的结论，而不是用今天改过的商品名与价格重算出的另一套数字
 * ——与 6.3 快照同源。<b>重跑同一日会覆盖</b>（{@code uk_report_date} 唯一）。</p>
 *
 * <p><b>生成失败也落库</b>：{@code aiText} 为空且 {@code degraded=1} 的行同样是有效记录——
 * 否则「那天为什么没有日报」无从追溯。</p>
 */
@Data
@TableName("daily_report")
public class DailyReport {

    /** 推送状态：未推送（默认，可选推送未开启时恒为此值）。 */
    public static final String PUSH_NONE = "NONE";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属日（按 app.time-zone 的自然日）。 */
    private LocalDate reportDate;

    /** 算好的指标快照 JSON（含与近 7 日同期的对比）。 */
    private String metricsJson;

    /** 异常项列表 JSON；空数组表示当日无异常（不得编造）。 */
    private String anomalyJson;

    /** AI 表述层产出的口语化日报；降级时为空。 */
    private String aiText;

    /** 1 = AI 不可用，仅纯数据版。 */
    private Boolean degraded;

    /** 推送状态 NONE / PENDING / SENT / FAILED。 */
    private String pushState;

    private LocalDateTime generatedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}