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

public final class ScheduleDtos {

    private ScheduleDtos() {
    }

    public record ScreenRef(UUID id, String name, String storeName, String city, String state) {
    }

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

    public record ConflictInfo(UUID scheduleId, String scheduleName, String window,
                               List<ScreenRef> screens) {
    }

    public record ConflictResponse(List<ConflictInfo> conflicts) {
    }
}
