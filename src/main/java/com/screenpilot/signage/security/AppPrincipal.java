package com.screenpilot.signage.security;

import com.screenpilot.signage.domain.Role;

import java.util.Set;
import java.util.UUID;

/**
 * Authenticated portal user attached to the security context.
 *
 * <p>A "principal" is Spring Security's word for "who is making this request". The
 * JwtAuthFilter builds one from a valid access token and stores it for the request;
 * code anywhere can then fetch it via {@link CurrentUser#get()}. Being a record it is
 * immutable, with constructor, accessors and equals/hashCode generated automatically.
 *
 * @param groupIds screen-group ids this user is restricted to; empty set means unrestricted
 */
public record AppPrincipal(UUID id, String email, String fullName, Role role, Set<UUID> groupIds) {

    /** True when the user may see every screen group. */
    public boolean unrestricted() {
        // SUPER_ADMIN always sees everything; for everyone else an EMPTY set means "no
        // restriction was configured" (not "no access") — a deliberate convention
        return role == Role.SUPER_ADMIN || groupIds.isEmpty();
    }

    /** True when the user may work with screens in the given group (or is unrestricted). */
    public boolean canAccessGroup(UUID groupId) {
        // a null groupId (an ungrouped screen) is visible only to unrestricted users;
        // everyone else must actually be assigned to that specific group
        return unrestricted() || (groupId != null && groupIds.contains(groupId));
    }
}
