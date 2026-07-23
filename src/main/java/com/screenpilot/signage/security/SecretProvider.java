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

    private final String secret;

    public SecretProvider(AppProperties props) {
        String configured = props.getJwt().getSecret();
        if (configured == null || configured.isBlank()) {
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            this.secret = Base64.getEncoder().encodeToString(random);
            log.warn("APP_JWT_SECRET is not set — generated an ephemeral secret. "
                    + "Sessions and signed links will not survive a restart. "
                    + "Set APP_JWT_SECRET (>=32 chars) for persistent deployments.");
        } else if (configured.length() < 32) {
            throw new IllegalStateException(
                    "APP_JWT_SECRET is too short (" + configured.length() + " chars) — use at least 32.");
        } else {
            this.secret = configured;
        }
    }

    public String getSecret() {
        return secret;
    }
}
