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

/**
 * DTOs (Data Transfer Objects) for the layout designer endpoints. Zone config travels as
 * a raw Jackson {@code JsonNode} because its shape differs per zone type. Each DTO is a
 * Java record; this outer class is only a namespace.
 */
public final class LayoutDtos {

    private LayoutDtos() {
    }

    /** Sent inside LayoutResponse: one zone's geometry, type, linked playlist and type-specific config. */
    public record ZoneResponse(
            UUID id,
            LayoutZone.Type type,
            double x, double y, double w, double h, int z,
            UUID playlistId,
            String playlistName,
            JsonNode config) {
    }

    /** Sent when listing or opening a layout in the designer. */
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

    /** Received inside SaveLayoutRequest: the edited state of one zone. */
    public record SaveZoneRequest(
            @NotNull LayoutZone.Type type,
            double x, double y, double w, double h,
            Integer z,
            UUID playlistId,
            JsonNode config) {
    }

    /** Received when saving the designer: replaces the layout's name, orientation and full zone list. */
    public record SaveLayoutRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull Screen.Orientation orientation,
            @NotNull List<SaveZoneRequest> zones) {
    }

    /** Received when creating a new layout, optionally seeded from a named zone preset. */
    public record CreateLayoutRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull Screen.Orientation orientation,
            String preset) {
    }
}
