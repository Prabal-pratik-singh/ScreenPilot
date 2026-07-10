package com.screenpilot.signage.security;

import com.screenpilot.signage.error.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Static accessors for the authenticated principal. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AppPrincipal get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppPrincipal p) {
            return p;
        }
        throw ApiException.unauthorized("Not authenticated");
    }

    public static DevicePrincipal device() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof DevicePrincipal p) {
            return p;
        }
        throw ApiException.unauthorized("Device not authenticated");
    }
}
