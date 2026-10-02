-- ============================================================================
-- V8 数据迁移（T68 · D5a AI 每日经营日报 + 异常预警）
-- 依据：任务卡「每日打烊后自动生成口语化日报」+「商家端首页卡片」。
-- 纪律：V1–V7 已执行不可改（Flyway Checksum 锁定）；本脚本只新增一张表。
--
-- 为什么必须落库而不是每次现算：
--   1. 日报是「打烊后生成的一次快照」——第二天再看昨天的日报，应当看到昨天的结论，
--      而不是用今天的商品改名/改价后重算出的另一套数字。这是与 6.3 快照同源的纪律。
--   2. 「无异常时明确说今日无异常，不编造」需要把**当时算出的异常项**存下来，
--      否则事后无法证明「那天确实没异常」。
--   3. 可选推送要在生成时刻拿到成品文案，不能依赖用户打开页面时现调模型。
--
-- 生成失败也要落库（ai_text 为空、degraded=1）：否则「那天为什么没日报」无从追溯。
-- ============================================================================

CREATE TABLE daily_report (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  report_date    DATE         NOT NULL COMMENT '归属日（按 app.time-zone 的自然日）',
  metrics_json   JSON         NOT NULL COMMENT '算好的指标快照（营业额/单量/杯数/渠道/TOP，含与近7日同期对比）',
  anomaly_json   JSON         NULL     COMMENT '异常项列表；为空数组表示当日无异常（不得编造）',
  ai_text        TEXT         NULL     COMMENT 'AI 表述层产出的口语化日报；降级时为空',
  degraded       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=AI 不可用，仅纯数据版',
  push_state     VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE / PENDING / SENT / FAILED（可选推送）',
  generated_at   DATETIME     NOT NULL,
  created_at     DATETIME     NOT NULL,
  updated_at     DATETIME     NOT NULL,
  UNIQUE KEY uk_report_date (report_date),
  KEY idx_generated (generated_at)
) COMMENT '每日经营日报（打烊后生成的快照，重跑覆盖同一日）';