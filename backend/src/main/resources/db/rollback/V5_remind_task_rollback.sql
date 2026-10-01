-- V5 回滚（T47）：撤销稍后提醒任务表
-- 说明：本表为纯增量新增表，无外键引用、无既有数据依赖，回滚即删表。
DROP TABLE IF EXISTS remind_task;
