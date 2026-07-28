package com.screenpilot.signage.integrations;

import org.springframework.stereotype.Component;

/**
 * Registered-but-disabled providers. Each needs real OAuth/API credentials
 * before it can be enabled — intentionally not faked.
 * Each nested class is a Spring {@code @Component}, so component scanning picks them up
 * and they appear automatically wherever a List of ContentSourceProvider is injected
 * (the Settings page renders one "Connect" card per bean). The outer class is only a
 * namespace grouping them.
 */
public final class StubProviders {

    private StubProviders() {
    }

    @Component
    public static class CanvaProvider implements ContentSourceProvider {
        public String id() { return "canva"; }
        public String displayName() { return "Canva"; }
        public String description() { return "Import designs straight from Canva folders and keep them in sync."; }
        public boolean isEnabled() { return false; }
        public String requirement() { return "Requires Canva Connect API credentials (client id + secret)."; }
    }

    @Component
    public static class GoogleDriveProvider implements ContentSourceProvider {
        public String id() { return "google-drive"; }
        public String displayName() { return "Google Drive"; }
        public String description() { return "Pull videos and images from shared Drive folders."; }
        public boolean isEnabled() { return false; }
        public String requirement() { return "Requires a Google Cloud OAuth client and Drive API scope."; }
    }

    @Component
    public static class OneDriveProvider implements ContentSourceProvider {
        public String id() { return "onedrive"; }
        public String displayName() { return "OneDrive"; }
        public String description() { return "Sync creative assets from OneDrive / SharePoint document libraries."; }
        public boolean isEnabled() { return false; }
        public String requirement() { return "Requires a Microsoft Entra app registration with Files.Read scope."; }
    }

    @Component
    public static class PowerBiProvider implements ContentSourceProvider {
        public String id() { return "power-bi"; }
        public String displayName() { return "Power BI"; }
        public String description() { return "Show live Power BI dashboards inside web zones."; }
        public boolean isEnabled() { return false; }
        public String requirement() { return "Requires Power BI embedded capacity and a service principal."; }
    }
}
