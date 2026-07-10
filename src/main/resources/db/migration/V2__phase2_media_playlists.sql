-- Phase 2: media library and playlists

CREATE TABLE media_assets (
    id               UUID PRIMARY KEY,
    name             VARCHAR(300) NOT NULL,
    type             VARCHAR(20)  NOT NULL, -- VIDEO | IMAGE | PDF
    mime_type        VARCHAR(120),
    size_bytes       BIGINT       NOT NULL DEFAULT 0,
    width            INTEGER,
    height           INTEGER,
    duration_seconds DOUBLE PRECISION,
    storage_path     VARCHAR(500) NOT NULL,
    thumb_path       VARCHAR(500),
    folder           VARCHAR(200),
    tags             TEXT,
    uploaded_by      UUID REFERENCES users (id) ON DELETE SET NULL,
    uploaded_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted_at       TIMESTAMPTZ
);

CREATE INDEX idx_media_type ON media_assets (type);
CREATE INDEX idx_media_folder ON media_assets (folder);
CREATE INDEX idx_media_deleted ON media_assets (deleted);

CREATE TABLE playlists (
    id          UUID PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    created_by  UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE playlist_items (
    id               UUID PRIMARY KEY,
    playlist_id      UUID        NOT NULL REFERENCES playlists (id) ON DELETE CASCADE,
    position         INTEGER     NOT NULL,
    item_type        VARCHAR(20) NOT NULL, -- MEDIA | URL | YOUTUBE
    media_id         UUID REFERENCES media_assets (id) ON DELETE CASCADE,
    url              TEXT,
    title            VARCHAR(300),
    duration_seconds INTEGER
);

CREATE INDEX idx_playlist_items_playlist ON playlist_items (playlist_id, position);
CREATE INDEX idx_playlist_items_media ON playlist_items (media_id);
