package com.screenpilot.signage.domain;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * JPA entity mapped to the "playlist_items" table. A row is one slot in a
 * {@link Playlist}: either an uploaded {@link MediaAsset}, an external web URL, or a
 * YouTube video, with its position in the play order and an optional display duration.
 */
@Entity
@Table(name = "playlist_items")
public class PlaylistItem {

    /** Source of the content: MEDIA = uploaded asset, URL = any web page, YOUTUBE = embedded YouTube video. */
    public enum ItemType { MEDIA, URL, YOUTUBE }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "playlist_id")
    private Playlist playlist;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false)
    private ItemType itemType;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "media_id")
    private MediaAsset media;

    private String url;

    private String title;

    /** Display seconds for images/PDF/external items; null for videos (play full length). */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    public PlaylistItem() {
    }

    public PlaylistItem(Playlist playlist, int position) {
        this.id = UUID.randomUUID();
        this.playlist = playlist;
        this.position = position;
    }

    public UUID getId() { return id; }
    public Playlist getPlaylist() { return playlist; }
    public void setPlaylist(Playlist playlist) { this.playlist = playlist; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public ItemType getItemType() { return itemType; }
    public void setItemType(ItemType itemType) { this.itemType = itemType; }
    public MediaAsset getMedia() { return media; }
    public void setMedia(MediaAsset media) { this.media = media; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }
}
