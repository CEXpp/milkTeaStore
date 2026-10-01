-- ============================================================================
-- V3 手动回滚脚本（T42 / ryHSSU）
-- 注意：Flyway 社区版默认不自动执行 undo；如需回退，请手动在数据库执行本文件。
-- 与 V3__feature_schema.sql 严格对称。
-- ============================================================================

DROP TABLE IF EXISTS pickup_token;
DROP TABLE IF EXISTS group_cart;
DROP TABLE IF EXISTS product_alias;

ALTER TABLE ai_message DROP COLUMN unmatched;
ALTER TABLE order_item DROP COLUMN member_tag;
ALTER TABLE orders DROP INDEX idx_group;
ALTER TABLE orders DROP COLUMN remark_tags;
ALTER TABLE orders DROP COLUMN eta_minutes;
ALTER TABLE orders DROP COLUMN group_id;
