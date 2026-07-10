package com.screenpilot.signage.security;

import java.util.UUID;

/** Authenticated player device (a paired screen). */
public record DevicePrincipal(UUID screenId, String screenName) {
}
