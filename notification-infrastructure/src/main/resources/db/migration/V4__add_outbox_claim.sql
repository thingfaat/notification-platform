ALTER TABLE notify_outbox
    ADD COLUMN locked_by VARCHAR(64) NULL AFTER next_retry_time,
    ADD COLUMN locked_at DATETIME(3) NULL AFTER locked_by;

CREATE INDEX idx_outbox_claim ON notify_outbox (publish_status, next_retry_time, locked_at, created_at);