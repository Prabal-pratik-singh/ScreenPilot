package com.screenpilot.signage.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * DTOs (Data Transfer Objects) for the player-device API — the endpoints the TV app
 * calls for pairing and heartbeats. Each is a Java record; this outer class is only a
 * namespace.
 */
public final class PlayerDtos {

    private PlayerDtos() {
    }

    /** Received from an unpaired TV asking for a pairing code; deviceInfo describes the hardware. */
    public record PairRequest(@Size(max = 500) String deviceInfo) {
    }

    /** Sent back with the 6-character code the TV shows on screen, and how often to poll. */
    public record PairCodeResponse(String code, Instant expiresAt, long pollIntervalMs) {
    }

    /** Sent while the TV polls: once status is PAIRED it includes the device token and screen identity. */
    public record PairPollResponse(String status, String deviceToken, UUID screenId, String screenName) {
    }

    /** Received periodically from each player: its health, what it is playing and its media cache state. */
    public record HeartbeatRequest(
            String status,
            String currentItemName,
            UUID currentItemMediaId,
            String appVersion,
            Double storageUsedMb,
            Double storageTotalMb,
            JsonNode mediaState) {
    }

    /** Sent back to acknowledge a heartbeat; serverTime lets the device sync its clock. */
    public record HeartbeatResponse(boolean ok, Instant serverTime) {
    }
}
