CREATE TABLE admin_user (
                            id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                            username      VARCHAR(64)  NOT NULL,
                            password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt',
                            nickname      VARCHAR(64)  NOT NULL DEFAULT '店长',
                            created_at    DATETIME     NOT NULL,
                            updated_at    DATETIME     NOT NULL,
                            UNIQUE KEY uk_username (username)
) COMMENT '商家账号（单账号）';

CREATE TABLE customer (
                          id             BIGINT AUTO_INCREMENT PRIMARY KEY,
                          openid         VARCHAR(64)  NOT NULL COMMENT '微信 openid',
                          nickname       VARCHAR(64)  NULL COMMENT '昵称（可选授权）',
                          created_at     DATETIME     NOT NULL,
                          last_active_at DATETIME     NOT NULL,
                          UNIQUE KEY uk_openid (openid)
) COMMENT '顾客（静默登录标识）';

CREATE TABLE category (
                          id         BIGINT AUTO_INCREMENT PRIMARY KEY,
                          name       VARCHAR(32) NOT NULL,
                          sort_order INT         NOT NULL DEFAULT 0,
                          created_at DATETIME    NOT NULL,
                          UNIQUE KEY uk_name (name)
) COMMENT '商品分类';

CREATE TABLE spec_group (
                            id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                            code         VARCHAR(32)  NOT NULL COMMENT 'CUP_SIZE/TEMPERATURE/SWEETNESS/TOPPING',
                            name         VARCHAR(32)  NOT NULL COMMENT '杯型/温度/甜度/加料',
                            multi_select TINYINT      NOT NULL DEFAULT 0 COMMENT '0 单选 1 多选（加料=1）',
                            enabled      TINYINT      NOT NULL DEFAULT 1,
                            sort_order   INT          NOT NULL DEFAULT 0
) COMMENT '全局规格组模板';

CREATE TABLE spec_option (
                             id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                             group_id    BIGINT       NOT NULL,
                             name        VARCHAR(32)  NOT NULL COMMENT '中杯/大杯/少冰/半糖/珍珠...',
                             price_delta DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '价差（杯型/加料计价，其余 0）',
                             enabled     TINYINT      NOT NULL DEFAULT 1,
                             sort_order  INT          NOT NULL DEFAULT 0,
                             KEY idx_group (group_id)
) COMMENT '规格项';

CREATE TABLE product (
                         id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                         category_id  BIGINT        NOT NULL,
                         name         VARCHAR(64)   NOT NULL,
                         description  VARCHAR(255)  NULL,
                         image_key    VARCHAR(255)  NULL COMMENT 'MinIO 对象 key（非完整 URL）',
                         base_price   DECIMAL(10,2) NOT NULL,
                         status       TINYINT       NOT NULL DEFAULT 1 COMMENT '1 上架 0 下架',
                         sort_order   INT           NOT NULL DEFAULT 0,
                         created_at   DATETIME      NOT NULL,
                         updated_at   DATETIME      NOT NULL,
                         KEY idx_category (category_id),
                         KEY idx_status (status)
) COMMENT '商品';

CREATE TABLE product_spec_group (
                                    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
                                    product_id BIGINT NOT NULL,
                                    group_id   BIGINT NOT NULL,
                                    UNIQUE KEY uk_product_group (product_id, group_id)
) COMMENT '商品适用的规格组（默认全部勾选）';
CREATE TABLE orders (
                        id             BIGINT AUTO_INCREMENT PRIMARY KEY,
                        order_no       VARCHAR(32)   NOT NULL COMMENT 'yyMMdd+5位序列',
                        source         VARCHAR(16)   NOT NULL COMMENT 'MINI_PROGRAM / AI / COUNTER',
                        status         VARCHAR(16)   NOT NULL COMMENT 'PENDING_PAYMENT/PAID/PREPARING/COMPLETED/CLOSED/VOIDED',
                        customer_id    BIGINT        NULL COMMENT '柜台单为 NULL',
                        pay_channel    VARCHAR(16)   NULL COMMENT 'MOCK / WECHAT（预留）',
                        transaction_id VARCHAR(64)   NULL COMMENT '支付流水号（Mock 生成 / 微信预留）',
                        total_amount   DECIMAL(10,2) NOT NULL COMMENT '应付总额（快照）',
                        pickup_code    VARCHAR(8)    NULL COMMENT '取餐码（进入 PAID 时分配）',
                        remark         VARCHAR(255)  NULL COMMENT '口味备注',
                        paid_at        DATETIME      NULL,
                        started_at     DATETIME      NULL COMMENT '开始制作时间',
                        completed_at   DATETIME      NULL COMMENT '出餐时间',
                        closed_at      DATETIME      NULL COMMENT '超时关闭时间',
                        voided_at      DATETIME      NULL,
                        void_reason    VARCHAR(255)  NULL,
                        created_at     DATETIME      NOT NULL,
                        updated_at     DATETIME      NOT NULL,
                        UNIQUE KEY uk_order_no (order_no),
                        KEY idx_status_created (status, created_at),
                        KEY idx_customer (customer_id, created_at),
                        KEY idx_pickup_date (pickup_code, paid_at)
) COMMENT '订单';

CREATE TABLE order_item (
                            id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                            order_id         BIGINT        NOT NULL,
                            product_id       BIGINT        NOT NULL COMMENT '关联商品（统计排行用）',
                            product_name     VARCHAR(64)   NOT NULL COMMENT '快照',
                            base_price       DECIMAL(10,2) NOT NULL COMMENT '快照基础价',
                            options_snapshot JSON          NULL COMMENT '[{group,option,delta}] 快照',
                            quantity         INT           NOT NULL DEFAULT 1,
                            unit_price       DECIMAL(10,2) NOT NULL COMMENT '快照单价=基础价+Σ价差',
                            item_amount      DECIMAL(10,2) NOT NULL COMMENT 'unit_price*quantity',
                            KEY idx_order (order_id),
                            KEY idx_product (product_id)
) COMMENT '订单项（快照）';

CREATE TABLE ai_session (
                            id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                            session_uuid VARCHAR(64)  NOT NULL,
                            customer_id  BIGINT       NOT NULL,
                            draft_items  JSON         NULL COMMENT '草稿单 [{productName,optionNames[],quantity}]',
                            expires_at   DATETIME     NOT NULL COMMENT '30 分钟无活动过期',
                            created_at   DATETIME     NOT NULL,
                            updated_at   DATETIME     NOT NULL,
                            UNIQUE KEY uk_uuid (session_uuid),
                            KEY idx_customer (customer_id),
                            KEY idx_expires (expires_at)
) COMMENT 'AI 会话（含草稿单）';

CREATE TABLE ai_message (
                            id         BIGINT AUTO_INCREMENT PRIMARY KEY,
                            session_id BIGINT      NOT NULL,
                            role       VARCHAR(16) NOT NULL COMMENT 'USER / ASSISTANT / TOOL',
                            content    TEXT        NOT NULL,
                            created_at DATETIME    NOT NULL,
                            KEY idx_session (session_id, id)
) COMMENT 'AI 会话消息历史';

CREATE TABLE shop_config (
                             id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                             config_key   VARCHAR(64)  NOT NULL,
                             config_value VARCHAR(255) NOT NULL,
                             UNIQUE KEY uk_key (config_key)
) COMMENT '门店配置 KV（paused 等）';

CREATE TABLE daily_seq (
                           id         BIGINT AUTO_INCREMENT PRIMARY KEY,
                           seq_date   DATE         NOT NULL,
                           seq_type   VARCHAR(16)  NOT NULL COMMENT 'ORDER_NO / PICKUP_CODE',
                           current_seq INT         NOT NULL DEFAULT 0,
                           UNIQUE KEY uk_date_type (seq_date, seq_type)
) COMMENT '每日流水（订单号/取餐码，行锁递增）';
