package com.screenpilot.signage.security;

import java.util.UUID;

// The device-side counterpart of AppPrincipal: DeviceTokenFilter attaches one of these
// when a TV presents a valid token. Devices carry no role or group data — screen id +
// name is everything the /api/player endpoints need to serve them. As a record it is
// immutable, with constructor, accessors and equals/hashCode generated automatically.
/** Authenticated player device (a paired screen). */
public record DevicePrincipal(UUID screenId, String screenName) {
}
