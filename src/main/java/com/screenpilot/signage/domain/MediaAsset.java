package com.screenpilot.signage.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapped to the "media_assets" table. A row is one uploaded file (video, image
 * or PDF) in the media library, plus its metadata (size, dimensions, duration, thumbnail).
 * The actual bytes live on disk via StorageService; this row only keeps the storage path.
 * Deletion is "soft": the row stays (flagged deleted) so playlists and reports keep working.
 */
@Entity
@Table(name = "media_assets")
public class MediaAsset {

    public enum Type { VIDEO, IMAGE, PDF }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(name = "mime_type")
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    private Integer width;

    private Integer height;

    @Column(name = "duration_seconds")
    private Double durationSeconds;

    /** Relative key under the upload directory where the file bytes are stored. */
    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @Column(name = "thumb_path")
    private String thumbPath;

    private String folder;

    /** Comma-separated tag list. */
    private String tags;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    @CreationTimestamp
    @Column(name = "uploaded_at", updatable = false)
    private Instant uploadedAt;

    /** Soft-delete flag: hidden from the library but kept for history/reports. */
    @Column(nullable = false)
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public MediaAsset() {
    }

    public MediaAsset(String name, Type type) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.type = type;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    public Double getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Double durationSeconds) { this.durationSeconds = durationSeconds; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public String getThumbPath() { return thumbPath; }
    public void setThumbPath(String thumbPath) { this.thumbPath = thumbPath; }
    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public User getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(User uploadedBy) { this.uploadedBy = uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
