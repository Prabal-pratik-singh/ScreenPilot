package com.screenpilot.signage.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "screen_commands")
public class ScreenCommand {

    public enum Command { RELOAD, CLEAR_CACHE, SCREENSHOT }

    public enum Status { SENT, ACKED, COMPLETED }

    @Id
    private UUID id;

    @Column(name = "screen_id", nullable = false)
    private UUID screenId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Command command;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.SENT;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "result_path")
    private String resultPath;

    public ScreenCommand() {
    }

    public ScreenCommand(UUID screenId, Command command, UUID requestedBy) {
        this.id = UUID.randomUUID();
        this.screenId = screenId;
        this.command = command;
        this.requestedBy = requestedBy;
    }

    public UUID getId() { return id; }
    public UUID getScreenId() { return screenId; }
    public Command getCommand() { return command; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public UUID getRequestedBy() { return requestedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getResultPath() { return resultPath; }
    public void setResultPath(String resultPath) { this.resultPath = resultPath; }
}
