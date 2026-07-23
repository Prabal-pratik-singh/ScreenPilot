package com.screenpilot.signage.security;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signed URLs: links to media binaries carry ?exp=<epoch>&sig=<hmac>
 * — like a movie ticket stamped for one show. Anyone without a fresh signature
 * gets 403, so leaked links die on expiry instead of living forever.
 *
 * Exposed via a static instance so record-based DTO factories can sign URLs
 * without threading a service through every call site.
 */
@Component
public class UrlSigner {

    private static volatile UrlSigner instance;

    private final byte[] key;

    public UrlSigner(SecretProvider secretProvider) {
        // derive a dedicated signing key from the app secret so the two uses never share raw key material
        this.key = hmac("screenpilot-url-signing".getBytes(StandardCharsets.UTF_8),
                secretProvider.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    @PostConstruct
    void register() {
        instance = this;
    }

    public static UrlSigner instance() {
        UrlSigner signer = instance;
        if (signer == null) {
            throw new IllegalStateException("UrlSigner not initialized yet");
        }
        return signer;
    }

    /** Returns "exp=...&sig=..." valid for ttlSeconds, bound to the given resource id. */
    public String signQuery(String resourceId, long ttlSeconds) {
        long exp = Instant.now().getEpochSecond() + ttlSeconds;
        return "exp=" + exp + "&sig=" + signature(resourceId, exp);
    }

    public boolean verify(String resourceId, Long exp, String sig) {
        if (exp == null || sig == null || exp < Instant.now().getEpochSecond()) {
            return false;
        }
        byte[] expected = signature(resourceId, exp).getBytes(StandardCharsets.UTF_8);
        byte[] actual = sig.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(expected, actual); // constant-time compare
    }

    private String signature(String resourceId, long exp) {
        return HexFormat.of().formatHex(hmac((resourceId + "|" + exp).getBytes(StandardCharsets.UTF_8), key));
    }

    private static byte[] hmac(byte[] message, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
