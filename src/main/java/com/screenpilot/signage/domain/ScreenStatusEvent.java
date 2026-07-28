package com.screenpilot.signage.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapped to the "screen_status_events" table. A row records the moment a
 * screen flipped ONLINE or OFFLINE. The Screen row only holds the current status, so
 * this append-only history is what uptime reports are computed from.
 */
@Entity
@Table(name = "screen_status_events")
public class ScreenStatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "screen_id", nullable = false)
    private UUID screenId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Screen.Status status;

    @Column(nullable = false)
    private Instant at;

    public ScreenStatusEvent() {
    }

    public ScreenStatusEvent(UUID screenId, Screen.Status status, Instant at) {
        this.screenId = screenId;
        this.status = status;
        this.at = at;
    }

    public Long getId() { return id; }
    public UUID getScreenId() { return screenId; }
    public Screen.Status getStatus() { return status; }
    public Instant getAt() { return at; }
}
