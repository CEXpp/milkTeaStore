-- ============================================================================
-- V3 数据迁移（T42 / ryHSSU · A4b）
-- 依据：T42 描述「V3 结构变更六项」+ LLD 第 11 章（group_cart / pickup_token，明确标注「随 T42 落地」）
-- 纪律：V1/V2 已执行不可改（Flyway Checksum 锁定）；本脚本全为增量，
--       新建表 / ALTER 仅加可空列，不影响历史数据与既有 checksum。
-- 命名冲突处理（透明标注，非臆造）：
--   - T42 描述「拼单实体（order_group 或同类）」→ 采用 LLD 11.2 定名 group_cart（「或同类」已授权）。
--   - pickup_token 未在 T42 文字列出，但 LLD 11.3 明确「随 T42 V3 迁移落地」且用户指定 → 纳入。
-- ============================================================================

-- 1) 拼单共享草稿池（LLD 11.2 DDL 直抄；对应 T42 第 1 项「拼单实体」）
CREATE TABLE group_cart (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  group_uuid   VARCHAR(64)  NOT NULL COMMENT '拼单池标识',
  owner_id     BIGINT       NOT NULL COMMENT '发起顾客 customer_id',
  shared_draft JSON         NOT NULL COMMENT '并入商品池：[{customerId, items:[{productName,optionNames[],quantity}]}]',
  member_ids   JSON         NULL COMMENT '参与方 customer_id 列表',
  version      BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  expires_at   DATETIME     NOT NULL COMMENT '30 分钟无活动过期',
  created_at   DATETIME     NOT NULL,
  updated_at   DATETIME     NOT NULL,
  UNIQUE KEY uk_group_uuid (group_uuid),
  KEY idx_owner (owner_id),
  KEY idx_expires (expires_at)
) COMMENT '拼单共享草稿池';

-- 2) 代取令牌（LLD 11.3 DDL 直抄；T42 描述未列但 LLD 明确归属 T42 + 用户指定）
CREATE TABLE pickup_token (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id    BIGINT       NOT NULL COMMENT '绑定订单',
  token       VARCHAR(64)  NOT NULL COMMENT '安全随机或签名 JWT',
  proxy_tag   VARCHAR(64)  NULL COMMENT '代取人标识（昵称/手机尾号），不可转赠锚点',
  expires_at  DATETIME     NOT NULL COMMENT '限时',
  used_at     DATETIME     NULL COMMENT '一次性：核销时间',
  revoked     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '可撤销标记',
  created_at  DATETIME     NOT NULL,
  UNIQUE KEY uk_token (token),
  KEY idx_order (order_id)
) COMMENT '代取凭证（限时+一次性+可撤销+不可转赠）';

-- 3) 商品别名自学习表（T42 第 4 项 product_alias；W06 菜单洞察 / 口味指纹）
CREATE TABLE product_alias (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_id   BIGINT       NOT NULL COMMENT '关联商品',
  alias_name   VARCHAR(64)  NOT NULL COMMENT '别名 / 口语化叫法',
  hit_count    INT          NOT NULL DEFAULT 1 COMMENT '命中次数（自学习权重）',
  created_at   DATETIME     NOT NULL,
  updated_at   DATETIME     NOT NULL,
  UNIQUE KEY uk_product_alias (product_id, alias_name),
  KEY idx_alias (alias_name)
) COMMENT '商品别名自学习（菜单洞察）';

-- 4) orders 加列（T42 第 3 项 eta_minutes + LLD 11.2 group_id + T42 第 5 项 remark_tags）
ALTER TABLE orders
  ADD COLUMN group_id    BIGINT NULL COMMENT '拼单关联 group_cart.id，非拼单为 NULL',
  ADD COLUMN eta_minutes INT    NULL COMMENT '到店申报预计等待（分钟）',
  ADD COLUMN remark_tags JSON   NULL COMMENT '结构化备注标签（语义解析结果，如[{tag,value}]）';
ALTER TABLE orders ADD KEY idx_group (group_id);

-- 5) order_item 加身份标记（T42 第 2 项；W13/W14 拼单成员归属）
ALTER TABLE order_item
  ADD COLUMN member_tag VARCHAR(64) NULL COMMENT '拼单成员标识（customer_id 或昵称），非拼单为 NULL';

-- 6) ai_message 加未匹配标记位（T42 第 6 项；W06 口味指纹 / 未理解）
ALTER TABLE ai_message
  ADD COLUMN unmatched TINYINT NOT NULL DEFAULT 0 COMMENT '1=AI 未能匹配 / 未理解';
