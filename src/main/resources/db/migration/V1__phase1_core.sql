-- Phase 1: core identity, screen groups, screens, pairing

CREATE TABLE screen_groups (
    id          UUID PRIMARY KEY,
    name        VARCHAR(120) NOT NULL UNIQUE,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(200) NOT NULL,
    role          VARCHAR(30)  NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_group_access (
    user_id  UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    group_id UUID NOT NULL REFERENCES screen_groups (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, group_id)
);

CREATE TABLE screens (
    id                    UUID PRIMARY KEY,
    name                  VARCHAR(200) NOT NULL,
    store_name            VARCHAR(200),
    city                  VARCHAR(120),
    state                 VARCHAR(120),
    group_id              UUID REFERENCES screen_groups (id) ON DELETE SET NULL,
    orientation           VARCHAR(20)  NOT NULL DEFAULT 'LANDSCAPE',
    resolution            VARCHAR(40),
    latitude              DOUBLE PRECISION,
    longitude             DOUBLE PRECISION,
    status                VARCHAR(20)  NOT NULL DEFAULT 'OFFLINE',
    last_heartbeat_at     TIMESTAMPTZ,
    current_item_name     VARCHAR(300),
    current_item_media_id UUID,
    app_version           VARCHAR(40),
    device_token          VARCHAR(120) UNIQUE,
    paired                BOOLEAN      NOT NULL DEFAULT FALSE,
    storage_used_mb       DOUBLE PRECISION,
    storage_total_mb      DOUBLE PRECISION,
    media_state           TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_screens_group ON screens (group_id);
CREATE INDEX idx_screens_status ON screens (status);
CREATE INDEX idx_screens_state_city ON screens (state, city);

CREATE TABLE pairing_codes (
    id          UUID PRIMARY KEY,
    code        VARCHAR(6)  NOT NULL,
    device_info VARCHAR(500),
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    screen_id   UUID REFERENCES screens (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX idx_pairing_code_pending ON pairing_codes (code) WHERE status = 'PENDING';
