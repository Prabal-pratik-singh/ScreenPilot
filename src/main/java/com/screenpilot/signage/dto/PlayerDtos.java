package com.screenpilot.signage.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class PlayerDtos {

    private PlayerDtos() {
    }

    public record PairRequest(@Size(max = 500) String deviceInfo) {
    }

    public record PairCodeResponse(String code, Instant expiresAt, long pollIntervalMs) {
    }

    public record PairPollResponse(String status, String deviceToken, UUID screenId, String screenName) {
    }

    public record HeartbeatRequest(
            String status,
            String currentItemName,
            UUID currentItemMediaId,
            String appVersion,
            Double storageUsedMb,
            Double storageTotalMb,
            JsonNode mediaState) {
    }

    public record HeartbeatResponse(boolean ok, Instant serverTime) {
    }
}
