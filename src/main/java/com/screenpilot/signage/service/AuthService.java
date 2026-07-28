package com.screenpilot.signage.service;

import com.screenpilot.signage.domain.User;
import com.screenpilot.signage.dto.AuthDtos;
import com.screenpilot.signage.dto.UserDtos;
import com.screenpilot.signage.repo.UserRepository;
import com.screenpilot.signage.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Handles portal login and token refresh. Verifies the password with the
 * configured PasswordEncoder (BCrypt) and hands out a JWT access/refresh
 * token pair via {@link JwtService}.
 */
@Service
public class AuthService {

    // collaborators: the users table, the BCrypt encoder bean (from SecurityConfig),
    // and the factory that mints/parses JWTs
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /** Constructor injection — Spring wires in the repository, encoder and JWT factory. */
    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    // Login flow. The identical "Invalid email or password" message for a missing
    // account and for a wrong password is deliberate: answering differently would let
    // an attacker probe which email addresses have accounts (user enumeration).
    // @Transactional(readOnly = true) wraps the method in one read-only DB transaction:
    // logins never write, and read-only lets JPA skip its change-tracking overhead.
    /** Checks email + password and returns fresh tokens; same error for unknown email and wrong password. */
    @Transactional(readOnly = true)
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest request) {
        // 1. find the account (case-insensitive email)
        //    trim() forgives copy-pasted whitespace; IgnoreCase treats Alice@x.com
        //    and alice@x.com as the same account
        User user = userRepository.findByEmailIgnoreCase(request.email().trim())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        // 2. deactivated accounts may not log in
        if (!user.isActive()) {
            throw new BadCredentialsException("This account has been deactivated");
        }
        // 3. compare the submitted password against the stored hash
        //    BCrypt re-hashes the submitted password using the salt embedded in the
        //    stored hash and compares the results — the plaintext is never stored
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }
        // 4. success — mint a fresh access/refresh pair plus the user's profile
        return tokens(user);
    }

    // Refresh flow ("token rotation"): the client trades its refresh token for a
    // brand-new access + refresh pair, so a session rolls forward without the user
    // re-typing a password. Nothing is stored server-side — the old refresh token
    // simply stays valid until its own expiry (stateless design); real revocation
    // happens by deactivating the user, which step 3 below checks on every renewal.
    /** Exchanges a valid refresh token for a brand-new access/refresh pair. */
    @Transactional(readOnly = true)
    public AuthDtos.TokenResponse refresh(AuthDtos.RefreshRequest request) {
        // 1. the token must parse and carry a valid signature
        Claims claims;
        try {
            claims = jwtService.parse(request.refreshToken());
        } catch (JwtException | IllegalArgumentException e) {
            throw new BadCredentialsException("Invalid refresh token");
        }
        // 2. access tokens are rejected here — only the "refresh" type may renew a session
        //    (otherwise a leaked short-lived access token could keep renewing itself forever)
        if (!JwtService.TYPE_REFRESH.equals(claims.get("type", String.class))) {
            throw new BadCredentialsException("Invalid refresh token");
        }
        // 3. the user must still exist and be active
        //    this DB check is what makes "deactivate user" an effective kill switch
        //    even while their refresh token is still cryptographically valid
        User user = userRepository.findById(UUID.fromString(claims.getSubject()))
                .orElseThrow(() -> new BadCredentialsException("User no longer exists"));
        if (!user.isActive()) {
            throw new BadCredentialsException("This account has been deactivated");
        }
        // 4. success — hand out a completely fresh token pair
        return tokens(user);
    }

    // builds the response bundle: access token + refresh token + user profile
    // (the profile rides along so the frontend can render the logged-in user
    // immediately without making a second request)
    private AuthDtos.TokenResponse tokens(User user) {
        return new AuthDtos.TokenResponse(
                jwtService.createAccessToken(user),
                jwtService.createRefreshToken(user),
                UserDtos.UserResponse.from(user));
    }
}
