package com.screenpilot.signage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Type-safe holder for every custom setting under the {@code app.*} prefix in
 * application.yml. {@code @ConfigurationProperties} means Spring reads the YAML values
 * and fills these nested objects automatically at startup, so the rest of the code can
 * ask for {@code props.getJwt().getSecret()} instead of parsing raw strings.
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final Storage storage = new Storage();
    private final Player player = new Player();
    private final Seed seed = new Seed();

    public Jwt getJwt() { return jwt; }
    public Cors getCors() { return cors; }
    public Storage getStorage() { return storage; }
    public Player getPlayer() { return player; }
    public Seed getSeed() { return seed; }

    /** JWT (JSON Web Token) settings: the signing secret and how long access/refresh tokens live. */
    public static class Jwt {
        private String secret;
        private long accessMinutes = 30;
        private long refreshDays = 14;

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getAccessMinutes() { return accessMinutes; }
        public void setAccessMinutes(long accessMinutes) { this.accessMinutes = accessMinutes; }
        public long getRefreshDays() { return refreshDays; }
        public void setRefreshDays(long refreshDays) { this.refreshDays = refreshDays; }
    }

    /** CORS (Cross-Origin Resource Sharing): which browser origins may call this API, e.g. the React dev server. */
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5174");

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }

    /** Local file storage: where uploaded media lives on disk and the per-file upload size cap. */
    public static class Storage {
        private String dir = "./uploads";
        private long maxFileMb = 500;

        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
        public long getMaxFileMb() { return maxFileMb; }
        public void setMaxFileMb(long maxFileMb) { this.maxFileMb = maxFileMb; }
    }

    /** Player device rules: how long without a heartbeat before a screen counts as offline, and pairing-code lifetime. */
    public static class Player {
        private long offlineAfterSeconds = 90;
        private long pairingCodeTtlMinutes = 15;

        public long getOfflineAfterSeconds() { return offlineAfterSeconds; }
        public void setOfflineAfterSeconds(long offlineAfterSeconds) { this.offlineAfterSeconds = offlineAfterSeconds; }
        public long getPairingCodeTtlMinutes() { return pairingCodeTtlMinutes; }
        public void setPairingCodeTtlMinutes(long pairingCodeTtlMinutes) { this.pairingCodeTtlMinutes = pairingCodeTtlMinutes; }
    }

    /** Bootstrap admin account created by the seeder on first run (override these defaults in production). */
    public static class Seed {
        private String adminEmail = "admin@screenpilot.in";
        private String adminPassword = "ScreenPilot@123";

        public String getAdminEmail() { return adminEmail; }
        public void setAdminEmail(String adminEmail) { this.adminEmail = adminEmail; }
        public String getAdminPassword() { return adminPassword; }
        public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
    }
}
