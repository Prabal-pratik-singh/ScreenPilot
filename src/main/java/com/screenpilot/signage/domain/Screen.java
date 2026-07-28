package com.screenpilot.signage.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapped to the "screens" table. A row is one physical display (a TV/player
 * device in a store): its identity and location, live health data reported by heartbeats
 * (status, current item, storage), and the device token that authenticates the paired
 * player app.
 */
@Entity
@Table(name = "screens")
public class Screen {

    public enum Orientation { LANDSCAPE, PORTRAIT }

    public enum Status { ONLINE, OFFLINE }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "store_name")
    private String storeName;

    private String city;

    private String state;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "group_id")
    private ScreenGroup group;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Orientation orientation = Orientation.LANDSCAPE;

    private String resolution;

    private Double latitude;

    private Double longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OFFLINE;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "current_item_name")
    private String currentItemName;

    @Column(name = "current_item_media_id")
    private UUID currentItemMediaId;

    @Column(name = "app_version")
    private String appVersion;

    /** Hashed credential the player app presents on every request; set during pairing. */
    @Column(name = "device_token", unique = true)
    private String deviceToken;

    @Column(nullable = false)
    private boolean paired = false;

    @Column(name = "storage_used_mb")
    private Double storageUsedMb;

    @Column(name = "storage_total_mb")
    private Double storageTotalMb;

    /** Raw JSON reported by the player: cached / downloading / failed media ids. */
    @Column(name = "media_state")
    private String mediaState;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Screen() {
    }

    public Screen(String name) {
        this.id = UUID.randomUUID();
        this.name = name;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public ScreenGroup getGroup() { return group; }
    public void setGroup(ScreenGroup group) { this.group = group; }
    public Orientation getOrientation() { return orientation; }
    public void setOrientation(Orientation orientation) { this.orientation = orientation; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Instant lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public String getCurrentItemName() { return currentItemName; }
    public void setCurrentItemName(String currentItemName) { this.currentItemName = currentItemName; }
    public UUID getCurrentItemMediaId() { return currentItemMediaId; }
    public void setCurrentItemMediaId(UUID currentItemMediaId) { this.currentItemMediaId = currentItemMediaId; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }
    public String getDeviceToken() { return deviceToken; }
    public void setDeviceToken(String deviceToken) { this.deviceToken = deviceToken; }
    public boolean isPaired() { return paired; }
    public void setPaired(boolean paired) { this.paired = paired; }
    public Double getStorageUsedMb() { return storageUsedMb; }
    public void setStorageUsedMb(Double storageUsedMb) { this.storageUsedMb = storageUsedMb; }
    public Double getStorageTotalMb() { return storageTotalMb; }
    public void setStorageTotalMb(Double storageTotalMb) { this.storageTotalMb = storageTotalMb; }
    public String getMediaState() { return mediaState; }
    public void setMediaState(String mediaState) { this.mediaState = mediaState; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
