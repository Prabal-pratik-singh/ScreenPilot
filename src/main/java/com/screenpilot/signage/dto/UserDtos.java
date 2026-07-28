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

/**
 * DTOs (Data Transfer Objects) for the user-management endpoints. Note the response
 * never includes the password hash — only safe, displayable fields. Each is a Java
 * record; this outer class is only a namespace.
 */
public final class UserDtos {

    private UserDtos() {
    }

    /** Sent as a minimal group reference (id + name) wherever full group data is unnecessary. */
    public record GroupRef(UUID id, String name) {
    }

    /** Sent when listing users or returning the logged-in profile. */
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

    /** Received when an admin creates an account; the plain password is hashed before storage. */
    public record CreateUserRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank String fullName,
            @NotNull Role role,
            List<UUID> groupIds) {
    }

    /** Received when editing a user; password and active are optional (null = leave unchanged). */
    public record UpdateUserRequest(
            @NotBlank String fullName,
            @NotNull Role role,
            @Size(min = 8, max = 72) String password,
            Boolean active,
            List<UUID> groupIds) {
    }
}
