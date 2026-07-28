package com.screenpilot.signage.domain;

/**
 * Portal user roles, from most to least powerful. Spring Security's role hierarchy
 * (see SecurityConfig) lets each role do everything the roles below it can:
 * SUPER_ADMIN manages everything, ADMIN runs day-to-day operations, CONTENT_MANAGER
 * edits media/playlists/schedules for their groups, VIEWER has read-only access.
 */
public enum Role {
    SUPER_ADMIN,
    ADMIN,
    CONTENT_MANAGER,
    VIEWER
}
