-- ============================================================================
-- V4 数据迁移（T44 · A4c 微信订阅消息推送）
-- 依据：SRS 3.1 授权例外（不冷启动弹窗）+ 3.6 进行中订单页 + 9.3 降级 + 第 8 章「顾客·订阅」接口组；
--       LLD 11.4「双信道取餐提醒」以微信订阅消息为第二信道，注明与 SSE 同源、不重复造数。
-- 纪律：V1/V2/V3 已执行不可改（Flyway Checksum 锁定）；本脚本仅新增一张表，不影响历史数据。
--
-- 为什么需要本表（微信官方契约，非臆测）：
--   小程序订阅消息为「一次性订阅」——用户授权一次 = 服务端可下发一条；次数用尽后
--   再调 /cgi-bin/message/subscribe/send 会返回 errcode=43101（用户未订阅消息）。
--   因此服务端必须自记「剩余可用次数」，才能满足任务卡验收项「同一次订阅不重复推送」。
-- ============================================================================

-- 微信订阅消息额度池：一行 = 某顾客对某业务模板的剩余可下发次数
CREATE TABLE wx_subscribe_quota (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id  BIGINT      NOT NULL COMMENT '顾客 customer.id',
  template_key VARCHAR(32) NOT NULL COMMENT '业务模板键：PREPARING / PICKUP（对应 wx.subscribe.* 配置节点）',
  remaining    INT         NOT NULL DEFAULT 0 COMMENT '剩余可下发次数（一次性订阅额度；授权 +1，成功下发 -1）',
  created_at   DATETIME    NOT NULL,
  updated_at   DATETIME    NOT NULL,
  UNIQUE KEY uk_customer_template (customer_id, template_key)
) COMMENT '微信订阅消息额度池（一次性订阅次数）';
