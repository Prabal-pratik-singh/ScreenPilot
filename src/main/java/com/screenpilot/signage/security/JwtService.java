package com.screenpilot.signage.security;

import com.screenpilot.signage.config.AppProperties;
import com.screenpilot.signage.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Creates and validates the portal's JWTs, signed with HMAC (HS256) using the
 * app secret. Two token types: short-lived "access" tokens sent on every API
 * call, and longer-lived "refresh" tokens used only to mint new access tokens.
 */
@Service
public class JwtService {

    // values of the custom "type" claim — how filters/services tell the tokens apart
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    // the HMAC signing key, built once from the shared app secret. HS256 is symmetric:
    // the same key signs and verifies — fine here because only this server does either
    private final SecretKey key;
    private final AppProperties props;

    /**
     * Builds the signing key from the app secret's UTF-8 bytes. HS256 needs at least
     * 32 bytes (256 bits) of key material — SecretProvider enforces that at startup.
     */
    public JwtService(AppProperties props, SecretProvider secretProvider) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(secretProvider.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** Builds a short-lived access token carrying the user's id, email, role and name. */
    public String createAccessToken(User user) {
        // capture "now" once so issued-at and expiry come from the same instant
        Instant now = Instant.now();
        return Jwts.builder()
                // "sub" (subject) = the user's UUID — who this token belongs to
                .subject(user.getId().toString())
                // type gates where the token may be used (see JwtAuthFilter); email,
                // role and name let the frontend show the user without an extra call
                .claim("type", TYPE_ACCESS)
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("name", user.getFullName())
                // expiry math: now + app.jwt.access-minutes from config; short-lived on
                // purpose — a leaked access token is only useful until this timestamp
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(props.getJwt().getAccessMinutes()))))
                // sign with the HMAC key (JJWT picks HS256 from the key type); any later
                // change to the payload breaks this signature
                .signWith(key)
                // serialize to the compact 3-part "header.payload.signature" string
                .compact();
    }

    /** Builds a long-lived refresh token (subject only) used to obtain new access tokens. */
    public String createRefreshToken(User user) {
        Instant now = Instant.now();
        // deliberately minimal payload: subject + type only. This token lives for days,
        // so any role/email snapshot inside it would go stale — the refresh endpoint
        // re-reads the user from the DB when minting new tokens anyway
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("type", TYPE_REFRESH)
                // expiry measured in days (app.jwt.refresh-days) instead of minutes
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofDays(props.getJwt().getRefreshDays()))))
                .signWith(key)
                .compact();
    }

    /**
     * Parses and validates a token. Returns claims or throws JwtException.
     */
    public Claims parse(String token) throws JwtException {
        // verifyWith checks the HMAC signature; parseSignedClaims additionally rejects
        // expired tokens — so returning at all means "authentic and still valid"
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
