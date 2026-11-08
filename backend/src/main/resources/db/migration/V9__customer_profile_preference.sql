-- ============================================================================
-- V9 数据迁移（顾客资料 + 长期口味偏好）
-- 依据：本轮升级的「用户模块」与「AI 点单长期记忆」两项。
-- 纪律：V1–V8 已执行不可改（Flyway Checksum 锁定）；本脚本只加一列、只加一张表。
--
-- 一、customer 加头像列
--   必须存 MinIO 对象 key，不能存微信 chooseAvatar 返回的本地临时路径：
--   临时路径形如 http://tmp/xxx 或 wxfile://xxx，只在当次运行的本机有效，
--   存进库里等于给顾客留了一张永远打不开的图（且下次装机就指向别人的临时文件）。
--
-- 二、customer_preference：长期口味偏好
--   本表**只从已成交订单隐式聚合**，不解析对话内容、不接受显式写入：
--     * 可接受的订单状态：PAID / PREPARING / COMPLETED（都代表已付款）；
--       PENDING_PAYMENT（未付）/ CLOSED（超时关单）/ VOIDED（已作废退款）一律不计，
--       否则「点错又退掉的那杯」会污染偏好。
--     * 偏好表里存的是**中文选项名**而不是下标：个人中心的「口味档案」要直接展示给顾客看，
--       而决策层编码进 state 时再由菜单映射表把中文名换成下标。
--       存下标的话，菜单改版后下标会错位到别的选项上——那是静默显示错口味。
--     * 样本不足（未达 preference.min-sample-orders）时也落一行（选项为空、sample_orders 记真值），
--       用于把「没有历史」与「还没算过」区分开，避免每次对话都重算。
--
-- source 列当前恒为 INFERRED：本方案明确不做「对话中自动记住偏好」（用户拍板的隐私口径），
-- 保留该列是为了让「这行是怎么来的」在库里可审计；将来若开放顾客显式设置，无需再改表。
-- ============================================================================

ALTER TABLE customer
    ADD COLUMN avatar_key VARCHAR(255) NULL COMMENT '微信头像对象 key（MinIO），非本地临时路径';

CREATE TABLE customer_preference (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id          BIGINT       NOT NULL COMMENT '顾客 id',
    option_names_json    JSON         NULL     COMMENT '[{"groupCode":"SWEETNESS","optionNames":["半糖"]}]，仅存达到频次阈值的项',
    favorite_product_name VARCHAR(64) NULL     COMMENT '常点商品中文名（按商品 id 聚合后取当前名称）',
    sample_orders        INT          NOT NULL DEFAULT 0 COMMENT '参与聚合的已成交订单数（阈值分母）',
    source               VARCHAR(16)  NOT NULL DEFAULT 'INFERRED' COMMENT 'INFERRED=仅由已成交订单隐式聚合',
    built_at             DATETIME     NOT NULL COMMENT '最近一次聚合时间（惰性补算的过期基准）',
    created_at           DATETIME     NOT NULL,
    updated_at           DATETIME     NOT NULL,
    UNIQUE KEY uk_customer (customer_id),
    KEY idx_built_at (built_at)
) COMMENT '顾客口味偏好（仅由已成交订单隐式聚合，不解析对话内容）';
