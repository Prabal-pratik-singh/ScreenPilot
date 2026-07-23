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

public final class ScreenDtos {

    private ScreenDtos() {
    }

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

    public record PairScreenRequest(
            @NotBlank @Size(min = 6, max = 6) String code,
            @NotNull SaveScreenRequest screen) {
    }

    public record BulkGroupRequest(
            @NotEmpty List<UUID> screenIds,
            UUID groupId) {
    }
}
