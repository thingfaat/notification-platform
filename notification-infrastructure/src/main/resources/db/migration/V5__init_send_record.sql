CREATE TABLE notify_send_record
(
    id                  BIGINT       NOT NULL,
    tenant_id           BIGINT       NOT NULL,
    message_id          BIGINT       NOT NULL,
    event_id            VARCHAR(64)  NOT NULL,
    attempt_no          INT          NOT NULL,
    channel_type        VARCHAR(32)  NOT NULL,
    idempotency_key     VARCHAR(128) NOT NULL,
    send_status         VARCHAR(32)  NOT NULL,
    provider_message_id VARCHAR(128),
    failure_code        VARCHAR(64),
    failure_reason      VARCHAR(1000),
    started_at          DATETIME(3)  NOT NULL,
    finished_at         DATETIME(3),
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_send_attempt (tenant_id, message_id, attempt_no),
    UNIQUE KEY uk_send_idempotency (tenant_id, idempotency_key),
    KEY idx_send_event (tenant_id, event_id),
    KEY idx_send_status (tenant_id, send_status)
);