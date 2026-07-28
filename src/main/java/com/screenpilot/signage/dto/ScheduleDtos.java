package com.screenpilot.signage.dto;

import com.screenpilot.signage.domain.Schedule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * DTOs (Data Transfer Objects) for the scheduling endpoints, including the conflict
 * check that runs before a schedule is saved. Each is a Java record; this outer class
 * is only a namespace.
 */
public final class ScheduleDtos {

    private ScheduleDtos() {
    }

    /** Sent as a lightweight screen reference — enough to label a target without the full entity. */
    public record ScreenRef(UUID id, String name, String storeName, String city, String state) {
    }

    /** Sent when listing/opening schedules; status is derived from active flag + date window. */
    public record ScheduleResponse(
            UUID id,
            String name,
            Schedule.ContentType contentType,
            UUID playlistId,
            String playlistName,
            UUID layoutId,
            String layoutName,
            boolean allDay,
            LocalTime startTime,
            LocalTime endTime,
            List<String> daysOfWeek,
            LocalDate dateFrom,
            LocalDate dateTo,
            boolean active,
            String status, // ACTIVE | PAUSED | UPCOMING | EXPIRED
            List<ScreenRef> screens,
            String createdByName,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** Received on create/update; overrideScheduleIds lists conflicts the user chose to override. */
    public record SaveScheduleRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull Schedule.ContentType contentType,
            UUID playlistId,
            UUID layoutId,
            @NotEmpty List<UUID> screenIds,
            boolean allDay,
            String startTime,   // "HH:mm" IST
            String endTime,     // "HH:mm" IST
            List<String> daysOfWeek,
            LocalDate dateFrom,
            LocalDate dateTo,
            List<UUID> overrideScheduleIds) {
    }

    /** Sent per clash: which existing schedule overlaps, in what time window, on which screens. */
    public record ConflictInfo(UUID scheduleId, String scheduleName, String window,
                               List<ScreenRef> screens) {
    }

    /** Sent by the conflict-check endpoint so the UI can warn before saving. */
    public record ConflictResponse(List<ConflictInfo> conflicts) {
    }
}
