-- ============================================================================
-- V5 数据迁移（T47 · C4b 下单前预期管理）
-- 依据：SRS 3.6 V1.1 补充「下单前预期管理提示」+ 任务卡 W17：
--       「结算页展示队列预估与『稍后提醒我再点』（用订阅消息推送），提醒送达后购物车内容不丢失」。
-- 纪律：V1–V4 已执行不可改（Flyway Checksum 锁定）；本脚本仅新增一张表，不影响历史数据。
--
-- 关键约束（任务卡）：本需求发生在「创建订单之前」，不得改变 3.5 下单流程与 6.1 超时关单规则，
--   因此「稍后提醒」只登记一条待发送任务，**不产生任何订单、不占用取餐码、不进 6.5 统计**。
-- ============================================================================

-- 稍后提醒任务：一行 = 一条「到点给某顾客推一条订阅消息」的待办
CREATE TABLE remind_task (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT      NOT NULL COMMENT '顾客 customer.id',
  remind_at   DATETIME    NOT NULL COMMENT '计划提醒时间（登记时刻 + 延迟分钟数）',
  status      VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待发 / SENT 已下发 / SKIPPED 无可用额度',
  created_at  DATETIME    NOT NULL,
  handled_at  DATETIME    NULL COMMENT '实际处理时间（无论成功与跳过）',
  note        VARCHAR(128) NULL COMMENT '处理结果说明（排查用，如微信 errcode）',
  KEY idx_due (status, remind_at),
  KEY idx_customer (customer_id)
) COMMENT '稍后提醒任务（T47 下单前预期管理；一次性，处理即终结）';
