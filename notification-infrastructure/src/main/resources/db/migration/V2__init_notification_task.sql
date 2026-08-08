CREATE TABLE notify_task
(
    id             BIGINT      NOT NULL,
    tenant_id      BIGINT      NOT NULL,
    application_id BIGINT      NOT NULL,
    request_id     VARCHAR(64) NOT NULL,
    template_id    BIGINT      NOT NULL,
    channel_type   VARCHAR(32) NOT NULL,
    task_status    VARCHAR(32) NOT NULL,
    schedule_time  DATETIME(3),
    total_count    INT         NOT NULL DEFAULT 0,
    success_count  INT         NOT NULL DEFAULT 0,
    failed_count   INT         NOT NULL DEFAULT 0,
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version        INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_request (tenant_id, application_id, request_id),
    KEY idx_task_status (tenant_id, task_status)
);

CREATE TABLE notify_message
(
    id                  BIGINT       NOT NULL,
    tenant_id           BIGINT       NOT NULL,
    task_id             BIGINT       NOT NULL,
    message_no          VARCHAR(64)  NOT NULL,
    receiver            VARCHAR(256) NOT NULL,
    template_params     JSON,
    rendered_content    TEXT         NOT NULL,
    message_status      VARCHAR(32)  NOT NULL,
    retry_count         INT          NOT NULL DEFAULT 0,
    next_retry_time     DATETIME(3),
    provider_message_id VARCHAR(128),
    failure_code        VARCHAR(64),
    failure_reason      VARCHAR(512),
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version             INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_no (tenant_id, message_no),
    KEY idx_task (tenant_id, task_id),
    KEY idx_message_status (tenant_id, message_status)
);