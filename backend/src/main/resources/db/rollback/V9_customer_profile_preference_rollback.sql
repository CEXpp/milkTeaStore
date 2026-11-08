-- V9 回滚：撤销顾客资料与长期口味偏好
-- 说明：customer_preference 为本轮新增表，无其他表外键依赖，回滚即删表。
-- 注意：删表会连同「偏好是怎么算出来的（样本数/构建时间）」一起丢失，回滚前请确认无需保留。
DROP TABLE IF EXISTS customer_preference;

-- customer.avatar_key 为本轮新增列。MySQL 8 不支持 DROP COLUMN IF EXISTS，
-- 故本语句要求该列确实存在（Flyway 已成功执行过 V9 是回滚的前提）。
-- 删列会丢失顾客已设置的头像引用（MinIO 中的对象本身仍在，可事后按 key 找回）。
ALTER TABLE customer DROP COLUMN avatar_key;
