package com.screenpilot.signage.dto;

import com.screenpilot.signage.domain.Layout;
import com.screenpilot.signage.domain.LayoutZone;
import com.screenpilot.signage.domain.Screen;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LayoutDtos {

    private LayoutDtos() {
    }

    public record ZoneResponse(
            UUID id,
            LayoutZone.Type type,
            double x, double y, double w, double h, int z,
            UUID playlistId,
            String playlistName,
            JsonNode config) {
    }

    public record LayoutResponse(
            UUID id,
            String name,
            Screen.Orientation orientation,
            int zoneCount,
            List<ZoneResponse> zones,
            String createdByName,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record SaveZoneRequest(
            @NotNull LayoutZone.Type type,
            double x, double y, double w, double h,
            Integer z,
            UUID playlistId,
            JsonNode config) {
    }

    public record SaveLayoutRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull Screen.Orientation orientation,
            @NotNull List<SaveZoneRequest> zones) {
    }

    public record CreateLayoutRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull Screen.Orientation orientation,
            String preset) {
    }
}
