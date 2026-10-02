-- ============================================================================
-- V7 数据迁移（T63 · A5 拼单后端）
-- 依据：LLD 11.2 拼单生命周期「草稿 → 冻结 → 转正式单」+ T63 任务卡四类并发/异常。
-- 纪律：V1–V6 已执行不可改（Flyway Checksum 锁定）；本脚本仅加可空列 + 建索引，不动历史数据。
--
-- 为什么必须加 status：T42 建的 group_cart 只有 expires_at 与 version，
--   却没有「这单拼单现在处于哪个阶段」的字段。缺了它就无法区分
--   「仍在收单」/「已冻结待支付」/「已转正式单」/「已过期」四种状态——
--   每次判断都得靠「比对时间 + 反查 orders.group_id」间接推断，
--   而冻结是并发写入的 CAS 目标（乐观锁 WHERE version=? AND status=?），
--   没有显式状态就没有可比较的锚点。四种状态两两组合的判断分散在多处时必然分叉。
--
-- 状态取值（与 group_cart 生命周期一一对应）：
--   OPEN      收单中：可加入、可改自己的选品
--   FROZEN    已冻结：发起人已确认，禁止加入与改单，等待其支付
--   CONVERTING支付中：发起人已发起支付，正在生成正式单（防重复提交）
--   CONVERTED 已转正式单：orders.group_id 已指向本拼单生成的订单
--   EXPIRED   已失效：到期未支付或发起人放弃，不生成任何订单
-- ============================================================================

ALTER TABLE group_cart
  ADD COLUMN status     VARCHAR(16) NOT NULL DEFAULT 'OPEN'
    COMMENT '拼单阶段 OPEN/FROZEN/CONVERTING/CONVERTED/EXPIRED（T63 收口）',
  ADD COLUMN frozen_at  DATETIME NULL
    COMMENT '发起人确认冻结的时间；未冻结为 NULL',
  ADD COLUMN order_id   BIGINT NULL
    COMMENT 'CONVERTED 后指向生成的 orders.id；未转单为 NULL';

-- 按状态捞「待清理的过期拼单」：定时任务每分钟扫一次，走索引而非全表
ALTER TABLE group_cart ADD KEY idx_status_expires (status, expires_at);

-- 反查「这个拼单是否已生成订单」：幂等与防重复下单都要靠它
ALTER TABLE group_cart ADD KEY idx_order (order_id);