package com.screenpilot.signage.security;

import com.screenpilot.signage.error.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

// How this works: Spring Security keeps the current request's Authentication in
// SecurityContextHolder — a ThreadLocal tied to the thread serving the request. These
// static helpers read it back, so any service can ask "who is calling?" without every
// controller having to pass the principal down through method parameters.
/** Static accessors for the authenticated principal. */
public final class CurrentUser {

    // utility class — never instantiated
    private CurrentUser() {
    }

    /** Returns the logged-in portal user, or throws 401 if the caller is not a user. */
    public static AppPrincipal get() {
        // whatever the auth filters put on this thread (null if nobody authenticated)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // pattern-match: only a portal user qualifies — devices and anonymous callers fail
        if (auth != null && auth.getPrincipal() instanceof AppPrincipal p) {
            return p;
        }
        // throwing 401 (instead of returning null) lets callers use the result without
        // null checks: past this line a real logged-in user is guaranteed
        throw ApiException.unauthorized("Not authenticated");
    }

    /** Returns the authenticated player device, or throws 401 if the caller is not a device. */
    public static DevicePrincipal device() {
        // mirror of get() for the TV side: only a DevicePrincipal (paired screen) passes
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof DevicePrincipal p) {
            return p;
        }
        throw ApiException.unauthorized("Device not authenticated");
    }
}
