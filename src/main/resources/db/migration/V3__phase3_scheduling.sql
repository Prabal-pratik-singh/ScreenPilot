-- Phase 3: scheduling, targets, proof-of-play logs

CREATE TABLE schedules (
    id           UUID PRIMARY KEY,
    name         VARCHAR(200) NOT NULL,
    content_type VARCHAR(20)  NOT NULL, -- PLAYLIST | LAYOUT
    playlist_id  UUID REFERENCES playlists (id) ON DELETE CASCADE,
    layout_id    UUID,                  -- FK added with the layouts table in phase 4
    all_day      BOOLEAN      NOT NULL DEFAULT TRUE,
    start_time   TIME,                  -- IST wall-clock
    end_time     TIME,                  -- IST wall-clock
    days_of_week VARCHAR(40),           -- e.g. 'MON,TUE,SAT'; NULL = every day
    date_from    DATE,                  -- IST dates; NULL = open-ended
    date_to      DATE,
    priority     INTEGER      NOT NULL DEFAULT 0,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by   UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_schedules_active ON schedules (active);
CREATE INDEX idx_schedules_playlist ON schedules (playlist_id);

CREATE TABLE schedule_targets (
    schedule_id UUID NOT NULL REFERENCES schedules (id) ON DELETE CASCADE,
    screen_id   UUID NOT NULL REFERENCES screens (id) ON DELETE CASCADE,
    PRIMARY KEY (schedule_id, screen_id)
);

CREATE INDEX idx_schedule_targets_screen ON schedule_targets (screen_id);

CREATE TABLE playback_logs (
    id               BIGSERIAL PRIMARY KEY,
    screen_id        UUID NOT NULL REFERENCES screens (id) ON DELETE CASCADE,
    schedule_id      UUID,
    playlist_id      UUID,
    item_id          UUID,
    media_id         UUID,
    item_title       VARCHAR(300),
    item_type        VARCHAR(20),
    started_at       TIMESTAMPTZ NOT NULL,
    ended_at         TIMESTAMPTZ NOT NULL,
    duration_seconds DOUBLE PRECISION NOT NULL DEFAULT 0
);

CREATE INDEX idx_playback_logs_screen_time ON playback_logs (screen_id, started_at);
CREATE INDEX idx_playback_logs_media_time ON playback_logs (media_id, started_at);
CREATE INDEX idx_playback_logs_time ON playback_logs (started_at);
