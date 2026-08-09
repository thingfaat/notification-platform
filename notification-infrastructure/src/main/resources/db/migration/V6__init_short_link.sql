CREATE TABLE short_link
(
    id             BIGINT        NOT NULL,
    tenant_id      BIGINT        NOT NULL,
    application_id BIGINT        NOT NULL,
    original_url   VARCHAR(2048) NOT NULL,
    expire_at      DATETIME(3)   NOT NULL,
    status         VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version        INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_short_link_application (tenant_id, application_id),
    KEY idx_short_link_expire (tenant_id, status, expire_at)
);

CREATE TABLE short_link_mapping
(
    id            BIGINT      NOT NULL,
    tenant_id     BIGINT      NOT NULL,
    short_link_id BIGINT      NOT NULL,
    short_code    VARCHAR(16) NOT NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_code (short_code),
    UNIQUE KEY uk_short_link_mapping (tenant_id, short_link_id),
    KEY idx_short_link_mapping_tenant (tenant_id)
);