package com.screenpilot.signage.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One-way fingerprint for device tokens: the database stores only
 * SHA-256(token), so a leaked database cannot impersonate screens.
 *
 * <p>Why a fast hash is fine here but would be wrong for passwords: device tokens are
 * long random strings (huge entropy), so guessing one is hopeless even at billions of
 * fast hashes per second. Passwords are short human choices — those need a slow,
 * salted algorithm (BCrypt, see SecurityConfig) to make guessing expensive.
 */
public final class TokenHasher {

    // utility class: the private constructor stops anyone from instantiating it
    private TokenHasher() {
    }

    /** Returns the lowercase hex SHA-256 digest of the given string. */
    public static String sha256Hex(String value) {
        try {
            // MessageDigest instances are not thread-safe — create a fresh one per call
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // digest() gives 32 raw bytes; hex-encoding yields the stable 64-character
            // lowercase string that is stored on the Screen's deviceToken field
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 ships with every JRE — reaching this means a broken runtime, so
            // convert the checked exception into an unchecked fail-fast error
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
