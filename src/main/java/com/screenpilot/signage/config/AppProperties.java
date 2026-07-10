package com.screenpilot.signage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

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

    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5174");

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }

    public static class Storage {
        private String dir = "./uploads";
        private long maxFileMb = 500;

        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
        public long getMaxFileMb() { return maxFileMb; }
        public void setMaxFileMb(long maxFileMb) { this.maxFileMb = maxFileMb; }
    }

    public static class Player {
        private long offlineAfterSeconds = 90;
        private long pairingCodeTtlMinutes = 15;

        public long getOfflineAfterSeconds() { return offlineAfterSeconds; }
        public void setOfflineAfterSeconds(long offlineAfterSeconds) { this.offlineAfterSeconds = offlineAfterSeconds; }
        public long getPairingCodeTtlMinutes() { return pairingCodeTtlMinutes; }
        public void setPairingCodeTtlMinutes(long pairingCodeTtlMinutes) { this.pairingCodeTtlMinutes = pairingCodeTtlMinutes; }
    }

    public static class Seed {
        private String adminEmail = "admin@screenpilot.in";
        private String adminPassword = "ScreenPilot@123";

        public String getAdminEmail() { return adminEmail; }
        public void setAdminEmail(String adminEmail) { this.adminEmail = adminEmail; }
        public String getAdminPassword() { return adminPassword; }
        public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
    }
}
