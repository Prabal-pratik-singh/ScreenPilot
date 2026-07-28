package com.screenpilot.signage.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapped to the "pairing_codes" table. A row is one short-lived 6-character
 * code shown on an unpaired TV. An admin types the code into the portal to link that
 * device to a Screen; the player polls with the code until pairing completes, then
 * collects its device token.
 */
@Entity
@Table(name = "pairing_codes")
public class PairingCode {

    /** PENDING = waiting for an admin, PAIRED = linked to a screen, EXPIRED = code timed out. */
    public enum Status { PENDING, PAIRED, EXPIRED }

    @Id
    private UUID id;

    @Column(nullable = false, length = 6)
    private String code;

    @Column(name = "device_info")
    private String deviceInfo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "screen_id")
    private Screen screen;

    /** Plaintext token held only until the player collects it; cleared on expiry. */
    @Column(name = "device_token_plain")
    private String deviceTokenPlain;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public PairingCode() {
    }

    public PairingCode(String code, String deviceInfo, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.deviceInfo = deviceInfo;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDeviceInfo() { return deviceInfo; }
    public void setDeviceInfo(String deviceInfo) { this.deviceInfo = deviceInfo; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Screen getScreen() { return screen; }
    public void setScreen(Screen screen) { this.screen = screen; }
    public String getDeviceTokenPlain() { return deviceTokenPlain; }
    public void setDeviceTokenPlain(String deviceTokenPlain) { this.deviceTokenPlain = deviceTokenPlain; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
