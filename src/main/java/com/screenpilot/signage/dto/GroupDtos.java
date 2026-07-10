package com.screenpilot.signage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class GroupDtos {

    private GroupDtos() {
    }

    public record GroupResponse(UUID id, String name, String description, long screenCount) {
    }

    public record SaveGroupRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description) {
    }
}
