-- =====================================================================
-- V2__init_data.sql
-- 种子数据：admin 账号 / 4 个默认分类 / 4 组规格模板及选项 /
--           6 个示例商品（默认关联全部规格组）/ shop_config 默认值
-- 依赖：V1__init_schema.sql
-- 注意：本文件一旦执行成功即不可再修改（Flyway Checksum 校验）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. admin_user：初始管理员（单账号）
--    username / password_hash 由 Flyway 占位符注入，
--    取值来自 application-dev.yml 的 admin.initial-* 配置
-- ---------------------------------------------------------------------
INSERT INTO admin_user (username, password_hash, nickname, created_at, updated_at)
VALUES ('${adminUsername}', '${adminPasswordHash}', '店长', NOW(), NOW());

-- ---------------------------------------------------------------------
-- 2. category：4 个默认分类
-- ---------------------------------------------------------------------
INSERT INTO category (id, name, sort_order, created_at) VALUES
                                                            (1, '奶茶', 1, NOW()),
                                                            (2, '果茶', 2, NOW()),
                                                            (3, '咖啡', 3, NOW()),
                                                            (4, '冰沙', 4, NOW());

-- ---------------------------------------------------------------------
-- 3. spec_group：4 组规格模板（code 为强约束，须与后端枚举一致）
--    仅 TOPPING（加料）multi_select=1，其余单选
-- ---------------------------------------------------------------------
INSERT INTO spec_group (id, code, name, multi_select, enabled, sort_order) VALUES
                                                                               (1, 'CUP_SIZE',    '杯型', 0, 1, 1),
                                                                               (2, 'TEMPERATURE', '温度', 0, 1, 2),
                                                                               (3, 'SWEETNESS',   '甜度', 0, 1, 3),
                                                                               (4, 'TOPPING',     '加料', 1, 1, 4);

-- ---------------------------------------------------------------------
-- 4. spec_option：规格选项（价差规则：杯型/加料计价，温度/甜度为 0）
-- ---------------------------------------------------------------------
-- 杯型：中杯为基准价，大杯 +3 元
INSERT INTO spec_option (id, group_id, name, price_delta, enabled, sort_order) VALUES
                                                                                   (1, 1, '中杯', 0.00, 1, 1),
                                                                                   (2, 1, '大杯', 3.00, 1, 2);

-- 温度：不计价（五档为示例，若 LLD 有明确档位请以 LLD 为准）
INSERT INTO spec_option (id, group_id, name, price_delta, enabled, sort_order) VALUES
                                                                                   (3, 2, '正常冰', 0.00, 1, 1),
                                                                                   (4, 2, '少冰',   0.00, 1, 2),
                                                                                   (5, 2, '去冰',   0.00, 1, 3),
                                                                                   (6, 2, '常温',   0.00, 1, 4),
                                                                                   (7, 2, '热饮',   0.00, 1, 5);

-- 甜度：不计价（五档为示例）
INSERT INTO spec_option (id, group_id, name, price_delta, enabled, sort_order) VALUES
                                                                                   (8,  3, '全糖',   0.00, 1, 1),
                                                                                   (9,  3, '七分糖', 0.00, 1, 2),
                                                                                   (10, 3, '半糖',   0.00, 1, 3),
                                                                                   (11, 3, '三分糖', 0.00, 1, 4),
                                                                                   (12, 3, '无糖',   0.00, 1, 5);

-- 加料：珍珠 / 椰果各 +2 元（多选）
INSERT INTO spec_option (id, group_id, name, price_delta, enabled, sort_order) VALUES
                                                                                   (13, 4, '珍珠', 2.00, 1, 1),
                                                                                   (14, 4, '椰果', 2.00, 1, 2);

-- ---------------------------------------------------------------------
-- 5. product：6 个示例商品
--    image_key 为 MinIO 对象 key 演示值（后续任务上传真实图片后可更新）
--    status：1 上架 / 0 下架
-- ---------------------------------------------------------------------
INSERT INTO product (id, category_id, name, description, image_key,
                     base_price, status, sort_order, created_at, updated_at) VALUES
                                                                                 (1, 1, '珍珠奶茶',     '经典手作珍珠，茶香浓郁',   'product/pearl-milk-tea.png',    12.00, 1, 1, NOW(), NOW()),
                                                                                 (2, 1, '波霸奶茶',     '大颗波霸珍珠，口感 Q 弹',  'product/boba-milk-tea.png',     13.00, 1, 2, NOW(), NOW()),
                                                                                 (3, 2, '芒果绿茶',     '新鲜芒果与绿茶的清爽组合', 'product/mango-green-tea.png',   15.00, 1, 1, NOW(), NOW()),
                                                                                 (4, 2, '百香果柠檬茶', '酸甜百香果碰撞鲜柠檬',     'product/passion-lemon-tea.png', 14.00, 1, 2, NOW(), NOW()),
                                                                                 (5, 3, '燕麦拿铁',     '浓缩咖啡配香浓燕麦奶',     'product/oat-latte.png',         16.00, 1, 1, NOW(), NOW()),
                                                                                 (6, 4, '芒果冰沙',     '整颗芒果打制，绵密冰爽',   'product/mango-smoothie.png',    15.00, 1, 1, NOW(), NOW());

-- ---------------------------------------------------------------------
-- 6. product_spec_group：示例商品默认关联全部 4 组规格（即"默认全选"）
--    笛卡尔积一次生成 6 商品 × 4 组 = 24 行；商品数变化时自动适配
-- ---------------------------------------------------------------------
INSERT INTO product_spec_group (product_id, group_id)
SELECT p.id, sg.id
FROM product p
         CROSS JOIN spec_group sg;

-- ---------------------------------------------------------------------
-- 7. shop_config：门店配置 KV 默认值，paused=false（营业中）
-- ---------------------------------------------------------------------
INSERT INTO shop_config (config_key, config_value) VALUES
    ('paused', 'false');
-- 如 LLD 约定其他默认键，首次执行前在此追加，例如：
-- ('shop_name', '示例奶茶店'),
