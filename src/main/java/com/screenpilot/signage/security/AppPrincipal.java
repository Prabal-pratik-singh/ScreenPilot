package com.screenpilot.signage.security;

import com.screenpilot.signage.domain.Role;

import java.util.Set;
import java.util.UUID;

/**
 * Authenticated portal user attached to the security context.
 *
 * @param groupIds screen-group ids this user is restricted to; empty set means unrestricted
 */
public record AppPrincipal(UUID id, String email, String fullName, Role role, Set<UUID> groupIds) {

    /** True when the user may see every screen group. */
    public boolean unrestricted() {
        return role == Role.SUPER_ADMIN || groupIds.isEmpty();
    }

    public boolean canAccessGroup(UUID groupId) {
        return unrestricted() || (groupId != null && groupIds.contains(groupId));
    }
}
