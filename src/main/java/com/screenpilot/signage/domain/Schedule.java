package com.screenpilot.signage.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * JPA entity mapped to the "schedules" table. A row answers "what plays where, and when":
 * it points to a playlist or layout, targets a set of screens (via the "schedule_targets"
 * join table), and limits playback by time window, weekdays and date range. When several
 * schedules match a screen at the same moment, the highest priority wins.
 */
@Entity
@Table(name = "schedules")
public class Schedule {

    /** What this schedule plays: a single playlist, or a multi-zone layout. */
    public enum ContentType { PLAYLIST, LAYOUT }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false)
    private ContentType contentType;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "playlist_id")
    private Playlist playlist;

    @Column(name = "layout_id")
    private UUID layoutId;

    @Column(name = "all_day", nullable = false)
    private boolean allDay = true;

    /** IST wall-clock window. */
    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    /** Comma list of MON..SUN; null = every day. */
    @Column(name = "days_of_week")
    private String daysOfWeek;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    /** Tie-breaker when schedules overlap on a screen: higher number beats lower. */
    @Column(nullable = false)
    private int priority = 0;

    @Column(nullable = false)
    private boolean active = true;

    // Target screens, stored in the "schedule_targets" many-to-many join table.
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "schedule_targets",
            joinColumns = @JoinColumn(name = "schedule_id"),
            inverseJoinColumns = @JoinColumn(name = "screen_id"))
    private Set<Screen> screens = new HashSet<>();

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Schedule() {
    }

    public Schedule(String name, ContentType contentType) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.contentType = contentType;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ContentType getContentType() { return contentType; }
    public void setContentType(ContentType contentType) { this.contentType = contentType; }
    public Playlist getPlaylist() { return playlist; }
    public void setPlaylist(Playlist playlist) { this.playlist = playlist; }
    public UUID getLayoutId() { return layoutId; }
    public void setLayoutId(UUID layoutId) { this.layoutId = layoutId; }
    public boolean isAllDay() { return allDay; }
    public void setAllDay(boolean allDay) { this.allDay = allDay; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    public String getDaysOfWeek() { return daysOfWeek; }
    public void setDaysOfWeek(String daysOfWeek) { this.daysOfWeek = daysOfWeek; }
    public LocalDate getDateFrom() { return dateFrom; }
    public void setDateFrom(LocalDate dateFrom) { this.dateFrom = dateFrom; }
    public LocalDate getDateTo() { return dateTo; }
    public void setDateTo(LocalDate dateTo) { this.dateTo = dateTo; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Set<Screen> getScreens() { return screens; }
    public void setScreens(Set<Screen> screens) { this.screens = screens; }
    public User getCreatedBy() { return createdBy; }
    public void setCreatedBy(User createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
