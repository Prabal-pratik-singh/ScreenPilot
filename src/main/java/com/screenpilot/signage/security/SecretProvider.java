package com.screenpilot.signage.security;

import com.screenpilot.signage.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Resolves the application secret that signs JWTs and media URLs.
 *
 * Production: set APP_JWT_SECRET (>= 32 chars) in the environment / .env file.
 * Bare local runs: if unset, a random ephemeral secret is generated so the app
 * still starts — with the side effect that logins/links die on every restart,
 * which is exactly the nudge to configure a real one.
 */
@Component
public class SecretProvider {

    private static final Logger log = LoggerFactory.getLogger(SecretProvider.class);

    // resolved once at startup, immutable afterwards — JWT signing and URL signing
    // both read this same value for the whole life of the process
    private final String secret;

    /**
     * Story: read app.jwt.secret (bound from the APP_JWT_SECRET env var) — if absent,
     * generate a random throwaway secret; if present but too short, refuse to start.
     */
    public SecretProvider(AppProperties props) {
        // the value Spring bound from configuration (application.yml / environment)
        String configured = props.getJwt().getSecret();
        if (configured == null || configured.isBlank()) {
            // dev-convenience path: 48 bytes from SecureRandom (cryptographically strong
            // randomness), Base64-encoded to a 64-char string — far above the 32 minimum
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            this.secret = Base64.getEncoder().encodeToString(random);
            // loud warning: everything signed with this secret (logins, media links)
            // dies the moment the process restarts, because it gets regenerated
            log.warn("APP_JWT_SECRET is not set — generated an ephemeral secret. "
                    + "Sessions and signed links will not survive a restart. "
                    + "Set APP_JWT_SECRET (>=32 chars) for persistent deployments.");
        } else if (configured.length() < 32) {
            // fail fast at boot: HS256 wants >= 256 bits (~32 chars) of key material; a
            // short secret would silently weaken every token the app ever signs, and
            // crashing now forces the operator to fix the deployment before it runs
            throw new IllegalStateException(
                    "APP_JWT_SECRET is too short (" + configured.length() + " chars) — use at least 32.");
        } else {
            // a properly configured secret — use it exactly as given
            this.secret = configured;
        }
    }

    /** The resolved secret — consumed by JwtService (tokens) and UrlSigner (signed links). */
    public String getSecret() {
        return secret;
    }
}
