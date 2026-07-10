package com.screenpilot.signage.integrations;

import java.util.List;
import java.util.Map;

/**
 * Extension point for pulling content from external platforms (Canva, Google
 * Drive, OneDrive, Power BI, …). A provider becomes usable once it is wired
 * with real API credentials and implements {@link #browse()}; until then it
 * surfaces as a disabled "Connect" card in Settings.
 */
public interface ContentSourceProvider {

    /** Stable identifier, e.g. "canva". */
    String id();

    String displayName();

    /** Short human description shown on the Settings card. */
    String description();

    /** True only when credentials are configured and the integration is usable. */
    boolean isEnabled();

    /** What is required to enable this provider (e.g. "Canva Connect API credentials"). */
    String requirement();

    /**
     * Lists importable items from the external source.
     * Implementations require {@link #isEnabled()} to be true.
     */
    default List<Map<String, Object>> browse() {
        throw new UnsupportedOperationException(displayName() + " is not connected. " + requirement());
    }
}
