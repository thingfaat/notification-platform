-- 点击事实与事件幂等
CREATE TABLE short_link_click
(
    id            BIGINT      NOT NULL,
    tenant_id     BIGINT      NOT NULL,
    event_id      VARCHAR(64) NOT NULL,
    short_link_id BIGINT      NOT NULL,
    short_code    VARCHAR(16) NOT NULL,
    visitor_key   CHAR(64)    NOT NULL,
    clicked_at    DATETIME(3) NOT NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_link_click_event (tenant_id, event_id),
    KEY idx_short_link_click_query (tenant_id, short_link_id, clicked_at)
);

-- 精确UV去重集合
CREATE TABLE short_link_daily_visitor
(
    id               BIGINT      NOT NULL,
    tenant_id        BIGINT      NOT NULL,
    short_link_id    BIGINT      NOT NULL,
    stat_date        DATE        NOT NULL,
    visitor_key      CHAR(64)    NOT NULL,
    first_clicked_at DATETIME(3) NOT NULL,
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_link_daily_visitor (tenant_id, short_link_id, stat_date, visitor_key)
);

-- 查询用聚合结果
CREATE TABLE short_link_click_stat_daily
(
    id            BIGINT      NOT NULL,
    tenant_id     BIGINT      NOT NULL,
    short_link_id BIGINT      NOT NULL,
    stat_date     DATE        NOT NULL,
    pv            BIGINT      NOT NULL DEFAULT 0,
    uv            BIGINT      NOT NULL DEFAULT 0,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_link_click_stat_daily (tenant_id, short_link_id, stat_date),
    KEY idx_short_link_click_stat_date (tenant_id, stat_date)
);