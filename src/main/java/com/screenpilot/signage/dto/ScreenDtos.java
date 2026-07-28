package com.screenpilot.signage.dto;

import com.screenpilot.signage.domain.Screen;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTOs (Data Transfer Objects) for the screen-management endpoints. Each is a Java
 * record; this outer class is only a namespace.
 */
public final class ScreenDtos {

    private ScreenDtos() {
    }

    /** Sent when listing screens or opening a screen detail: static info plus live health fields. */
    public record ScreenResponse(
            UUID id,
            String name,
            String storeName,
            String city,
            String state,
            UserDtos.GroupRef group,
            Screen.Orientation orientation,
            String resolution,
            Double latitude,
            Double longitude,
            Screen.Status status,
            Instant lastHeartbeatAt,
            Long offlineSeconds,
            String currentItemName,
            UUID currentItemMediaId,
            String currentItemThumbUrl,
            String appVersion,
            boolean paired,
            Double storageUsedMb,
            Double storageTotalMb,
            JsonNode mediaState,
            Instant createdAt) {
    }

    /** Received on create/update of a screen's descriptive details (name, store, location, orientation). */
    public record SaveScreenRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String storeName,
            @Size(max = 120) String city,
            @Size(max = 120) String state,
            UUID groupId,
            @NotNull Screen.Orientation orientation,
            @Size(max = 40) String resolution,
            Double latitude,
            Double longitude) {
    }

    /** Received when an admin pairs a device: the 6-character code from the TV plus the screen's details. */
    public record PairScreenRequest(
            @NotBlank @Size(min = 6, max = 6) String code,
            @NotNull SaveScreenRequest screen) {
    }

    /** Received to move many screens into a group at once (null groupId = remove from group). */
    public record BulkGroupRequest(
            @NotEmpty List<UUID> screenIds,
            UUID groupId) {
    }
}
