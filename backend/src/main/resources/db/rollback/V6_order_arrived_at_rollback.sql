-- V6 回滚（T49）：撤销到店握手信号列
-- 说明：本列为纯增量可空列，无数据依赖；回滚即删列。
-- 注意：MySQL 8 支持 DROP COLUMN；若该列上有索引需先删索引（本列未建索引）。
ALTER TABLE orders DROP COLUMN arrived_at;
