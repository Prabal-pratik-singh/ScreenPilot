package com.screenpilot.signage.dto;

import com.screenpilot.signage.domain.Role;
import com.screenpilot.signage.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class UserDtos {

    private UserDtos() {
    }

    public record GroupRef(UUID id, String name) {
    }

    public record UserResponse(UUID id, String email, String fullName, Role role, boolean active,
                               List<GroupRef> groups, Instant createdAt) {

        public static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.isActive(),
                    u.getGroups().stream()
                            .map(g -> new GroupRef(g.getId(), g.getName()))
                            .sorted(Comparator.comparing(GroupRef::name))
                            .toList(),
                    u.getCreatedAt());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank String fullName,
            @NotNull Role role,
            List<UUID> groupIds) {
    }

    public record UpdateUserRequest(
            @NotBlank String fullName,
            @NotNull Role role,
            @Size(min = 8, max = 72) String password,
            Boolean active,
            List<UUID> groupIds) {
    }
}
