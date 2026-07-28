package com.screenpilot.signage.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * DTOs (Data Transfer Objects) for the login endpoints. DTOs are simple request/response
 * shapes that keep the JSON API decoupled from the JPA entities; each is a Java record
 * (an immutable data carrier). This outer class is just a namespace and is never instantiated.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** Received on POST /api/auth/login: the user's credentials. */
    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    /** Received when the access token expires: trades a refresh token for a new pair. */
    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    /** Sent back after a successful login/refresh: both JWTs plus the user's profile. */
    public record TokenResponse(String accessToken, String refreshToken, UserDtos.UserResponse user) {
    }
}
