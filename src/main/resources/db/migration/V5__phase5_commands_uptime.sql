-- Phase 5: remote commands + screen status history (uptime reporting)

CREATE TABLE screen_commands (
    id           UUID PRIMARY KEY,
    screen_id    UUID        NOT NULL REFERENCES screens (id) ON DELETE CASCADE,
    command      VARCHAR(30) NOT NULL, -- RELOAD | CLEAR_CACHE | SCREENSHOT
    status       VARCHAR(20) NOT NULL DEFAULT 'SENT', -- SENT | ACKED | COMPLETED
    requested_by UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    result_path  VARCHAR(500)
);

CREATE INDEX idx_screen_commands_screen ON screen_commands (screen_id, created_at DESC);

CREATE TABLE screen_status_events (
    id        BIGSERIAL PRIMARY KEY,
    screen_id UUID        NOT NULL REFERENCES screens (id) ON DELETE CASCADE,
    status    VARCHAR(20) NOT NULL, -- ONLINE | OFFLINE
    at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_status_events_screen_time ON screen_status_events (screen_id, at);
