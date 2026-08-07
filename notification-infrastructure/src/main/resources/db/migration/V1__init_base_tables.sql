-- 租户表
CREATE TABLE sys_tenant
(
    id          BIGINT       NOT NULL,
    tenant_code VARCHAR(64)  NOT NULL,
    tenant_name VARCHAR(128) NOT NULL,
    status      TINYINT      NOT NULL DEFAULT 1,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (tenant_code)
);

-- 业务应用
CREATE TABLE sys_application
(
    id         BIGINT       NOT NULL,
    tenant_id  BIGINT       NOT NULL,
    app_code   VARCHAR(64)  NOT NULL,
    app_name   VARCHAR(128) NOT NULL,
    status     TINYINT      NOT NULL DEFAULT 1,
    deleted    TINYINT      NOT NULL DEFAULT 0,
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version    INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_app_code (tenant_id,app_code),
    KEY        idx_tenant_id (tenant_id)
);

-- 通知渠道
CREATE TABLE notify_channel_account
(
    id             BIGINT       NOT NULL,
    tenant_id      BIGINT       NOT NULL,
    application_id BIGINT       NOT NULL,
    account_code   VARCHAR(64)  NOT NULL,
    account_name   VARCHAR(128) NOT NULL,
    channel_type   VARCHAR(32)  NOT NULL,
    provider       VARCHAR(64),
    config_json    JSON,
    status         TINYINT      NOT NULL DEFAULT 1,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version        INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_account (tenant_id,application_id,account_code),
    KEY            idx_application (tenant_id,application_id)
);

-- 通知模板
CREATE TABLE notify_template
(
    id               BIGINT       NOT NULL,
    tenant_id        BIGINT       NOT NULL,
    application_id   BIGINT       NOT NULL,
    template_code    VARCHAR(64)  NOT NULL,
    template_name    VARCHAR(128) NOT NULL,
    channel_type     VARCHAR(32)  NOT NULL,
    template_content TEXT         NOT NULL,
    variable_schema  JSON,
    status           TINYINT      NOT NULL DEFAULT 1,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version          INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_template (tenant_id,application_id,template_code),
    KEY              idx_application_channel (tenant_id,application_id,channel_type)
);