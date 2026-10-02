-- V7 回滚（T63）：撤销拼单阶段字段
-- 说明：status / frozen_at / order_id 均为本次新增，V7 之后若有业务数据落库，
--       回滚前需先清理 group_cart 数据（这些列只承载阶段标记，无历史数据依赖，
--       但 CONVERTED 行的 order_id 是唯一线索，回滚后无法恢复）。
ALTER TABLE group_cart
  DROP KEY idx_order,
  DROP KEY idx_status_expires,
  DROP COLUMN order_id,
  DROP COLUMN frozen_at,
  DROP COLUMN status;