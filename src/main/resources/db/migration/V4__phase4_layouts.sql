-- Phase 4: multi-zone layouts

CREATE TABLE layouts (
    id          UUID PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    orientation VARCHAR(20)  NOT NULL DEFAULT 'LANDSCAPE',
    created_by  UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE layout_zones (
    id          UUID PRIMARY KEY,
    layout_id   UUID             NOT NULL REFERENCES layouts (id) ON DELETE CASCADE,
    type        VARCHAR(20)      NOT NULL, -- MEDIA | TICKER | WIDGET | LOGO | WEB
    x           DOUBLE PRECISION NOT NULL, -- percentages of the canvas
    y           DOUBLE PRECISION NOT NULL,
    w           DOUBLE PRECISION NOT NULL,
    h           DOUBLE PRECISION NOT NULL,
    z           INTEGER          NOT NULL DEFAULT 1,
    playlist_id UUID REFERENCES playlists (id) ON DELETE SET NULL,
    config      TEXT             -- JSON per zone type (ticker text, widget kind, logo media, web url)
);

CREATE INDEX idx_layout_zones_layout ON layout_zones (layout_id);

ALTER TABLE schedules
    ADD CONSTRAINT fk_schedules_layout FOREIGN KEY (layout_id) REFERENCES layouts (id) ON DELETE CASCADE;
