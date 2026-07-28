package com.screenpilot.signage.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapped to the "playback_logs" table. A row records one "proof of play":
 * a specific item that a specific screen actually displayed, with start/end times.
 * Reports and exports are built from these rows. IDs are stored as plain UUID columns
 * (not foreign-key relations) so logs survive even if the playlist/media is later deleted;
 * itemTitle/itemType are denormalized copies kept for the same reason.
 */
@Entity
@Table(name = "playback_logs")
public class PlaybackLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "screen_id", nullable = false)
    private UUID screenId;

    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Column(name = "playlist_id")
    private UUID playlistId;

    @Column(name = "item_id")
    private UUID itemId;

    @Column(name = "media_id")
    private UUID mediaId;

    @Column(name = "item_title")
    private String itemTitle;

    @Column(name = "item_type")
    private String itemType;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false)
    private Instant endedAt;

    @Column(name = "duration_seconds", nullable = false)
    private double durationSeconds;

    public PlaybackLog() {
    }

    public Long getId() { return id; }
    public UUID getScreenId() { return screenId; }
    public void setScreenId(UUID screenId) { this.screenId = screenId; }
    public UUID getScheduleId() { return scheduleId; }
    public void setScheduleId(UUID scheduleId) { this.scheduleId = scheduleId; }
    public UUID getPlaylistId() { return playlistId; }
    public void setPlaylistId(UUID playlistId) { this.playlistId = playlistId; }
    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }
    public UUID getMediaId() { return mediaId; }
    public void setMediaId(UUID mediaId) { this.mediaId = mediaId; }
    public String getItemTitle() { return itemTitle; }
    public void setItemTitle(String itemTitle) { this.itemTitle = itemTitle; }
    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public double getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(double durationSeconds) { this.durationSeconds = durationSeconds; }
}
