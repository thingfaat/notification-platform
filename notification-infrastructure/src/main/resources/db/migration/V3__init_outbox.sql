CREATE TABLE notify_outbox
(
    id              BIGINT       NOT NULL,
    tenant_id       BIGINT       NOT NULL,
    event_id        VARCHAR(64)  NOT NULL,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    BIGINT       NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    topic           VARCHAR(128) NOT NULL,
    payload         JSON         NOT NULL,
    publish_status  VARCHAR(32)  NOT NULL,
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_time DATETIME(3),
    last_error      VARCHAR(1000),
    published_at    DATETIME(3),
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event (tenant_id, event_id),
    KEY idx_outbox_publish (publish_status, next_retry_time)
);

CREATE TABLE notify_consume_record
(
    id             BIGINT       NOT NULL,
    tenant_id      BIGINT       NOT NULL,
    consumer_group VARCHAR(128) NOT NULL,
    event_id       VARCHAR(64)  NOT NULL,
    message_id     BIGINT       NOT NULL,
    consumed_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_consume_event (consumer_group, event_id)
);