package com.screenpilot.signage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * DTOs (Data Transfer Objects) for the screen-group CRUD endpoints. Each is a Java
 * record; this outer class is only a namespace.
 */
public final class GroupDtos {

    private GroupDtos() {
    }

    /** Sent when listing groups; screenCount is computed, not stored on the entity. */
    public record GroupResponse(UUID id, String name, String description, long screenCount) {
    }

    /** Received on create and update of a group (bean-validation limits the field sizes). */
    public record SaveGroupRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description) {
    }
}
