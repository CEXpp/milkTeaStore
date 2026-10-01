-- V4 回滚（T44）：撤销微信订阅消息额度池
-- 说明：本表为纯增量新增表，无外键引用、无既有数据依赖，回滚即删表。
DROP TABLE IF EXISTS wx_subscribe_quota;
