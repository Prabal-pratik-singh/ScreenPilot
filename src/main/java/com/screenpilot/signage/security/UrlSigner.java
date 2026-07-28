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
 *
 * <p>Callers namespace the resource ids ("media:&lt;id&gt;", "screenshot:&lt;id&gt;"),
 * so a signature minted for one kind of resource can never unlock another kind.
 */
@Component
public class UrlSigner {

    // the singleton reference for static access; volatile so the value written by the
    // Spring startup thread is safely visible to every request-handling thread
    private static volatile UrlSigner instance;

    // dedicated URL-signing key — derived from, but never equal to, the app secret
    private final byte[] key;

    public UrlSigner(SecretProvider secretProvider) {
        // derive a dedicated signing key from the app secret so the two uses never share raw key material
        // (HMAC over a fixed label works as a one-way key-derivation step: this derived
        // key cannot be turned back into the JWT secret, and vice versa)
        this.key = hmac("screenpilot-url-signing".getBytes(StandardCharsets.UTF_8),
                secretProvider.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** Runs once Spring has fully constructed the bean — publishes it for static access. */
    @PostConstruct
    void register() {
        instance = this;
    }

    /** Static access for DTO factories; the Spring bean registers itself in @PostConstruct. */
    public static UrlSigner instance() {
        // copy the volatile field into a local so it is read exactly once
        UrlSigner signer = instance;
        // only possible before Spring finished startup (or in a bare unit test)
        if (signer == null) {
            throw new IllegalStateException("UrlSigner not initialized yet");
        }
        return signer;
    }

    /** Returns "exp=...&sig=..." valid for ttlSeconds, bound to the given resource id. */
    public String signQuery(String resourceId, long ttlSeconds) {
        // expiry as a unix timestamp (seconds since 1970) — compact and timezone-free
        long exp = Instant.now().getEpochSecond() + ttlSeconds;
        // callers append this to a URL, e.g. .../file?exp=1720000000&sig=ab12cd...
        return "exp=" + exp + "&sig=" + signature(resourceId, exp);
    }

    /** Checks a link's exp/sig pair: reject when missing, expired, or the HMAC does not match. */
    public boolean verify(String resourceId, Long exp, String sig) {
        // 1. missing parameters or a past expiry time fail immediately
        if (exp == null || sig == null || exp < Instant.now().getEpochSecond()) {
            return false;
        }
        // 2. recompute the expected HMAC-SHA256 over "resourceId|exp" and compare
        //    (exp is part of the signed message, so editing a URL's exp to stretch a
        //    link's lifetime changes the required signature and fails verification)
        byte[] expected = signature(resourceId, exp).getBytes(StandardCharsets.UTF_8);
        byte[] actual = sig.getBytes(StandardCharsets.UTF_8);
        // MessageDigest.isEqual always compares every byte; a plain equals() returns at
        // the first mismatch, and that timing difference can leak how many leading
        // bytes were correct — enough for an attacker to forge a sig byte by byte
        return java.security.MessageDigest.isEqual(expected, actual); // constant-time compare
    }

    /** The sig= value: HMAC-SHA256 over "resourceId|exp", hex-encoded. */
    private String signature(String resourceId, long exp) {
        // signing id and expiry together binds the signature to exactly this resource
        // and this deadline — a sig for media:42 is useless for media:43 or another exp
        return HexFormat.of().formatHex(hmac((resourceId + "|" + exp).getBytes(StandardCharsets.UTF_8), key));
    }

    /** Plain JCA HMAC-SHA256 of message under key. */
    private static byte[] hmac(byte[] message, byte[] key) {
        try {
            // Mac objects are cheap to create and NOT thread-safe — one per call
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (Exception e) {
            // HmacSHA256 is mandatory in every JRE, so this is effectively unreachable;
            // wrap the checked exception instead of forcing throws on every caller
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
