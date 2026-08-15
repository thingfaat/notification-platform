-- 业务类型用于隔离不同的幂等语义：
-- MANAGEMENT 使用客户端 requestId；
-- MESSAGE_TRACKING 使用 messageId + targetUrl 摘要
ALTER TABLE short_link
    ADD COLUMN business_type   VARCHAR(32)  NOT NULL DEFAULT 'MANAGEMENT'
        AFTER application_id,
    ADD COLUMN idempotency_key VARCHAR(128) NULL
        AFTER business_type;

-- 历史数据创建时没有 requestId。
-- 每条历史记录使用自己的主键生成唯一占位值，避免迁移时错误合并数据。
UPDATE short_link
SET idempotency_key = CONCAT('legacy:', id)
WHERE idempotency_key IS NULL;

-- 完成历史回填后再改成 NOT NULL，保证未来每条短链都有明确业务身份。
ALTER TABLE short_link
    MODIFY COLUMN idempotency_key VARCHAR(128) NOT NULL;

-- 数据库是并发幂等的最终裁决者。
-- 相同 requestId 可以在不同租户或不同应用下合法使用。
ALTER TABLE short_link
    ADD UNIQUE KEY uk_short_link_idempotency
        (tenant_id, application_id, business_type, idempotency_key);