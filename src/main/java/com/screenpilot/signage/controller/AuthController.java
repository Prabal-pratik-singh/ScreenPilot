package com.screenpilot.signage.web;

import com.screenpilot.signage.dto.AuthDtos;
import com.screenpilot.signage.dto.UserDtos;
import com.screenpilot.signage.repo.UserRepository;
import com.screenpilot.signage.security.CurrentUser;
import com.screenpilot.signage.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * Login endpoints for portal users. /login and /refresh are open to everyone
 * (they ARE how you authenticate); /me needs a valid Bearer token.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, UserRepository userRepository) {
        this.authService = authService;
        this.userRepository = userRepository;
    }

    // POST /api/auth/login — email + password in, access/refresh tokens out; public (rate-limited)
    @PostMapping("/login")
    public AuthDtos.TokenResponse login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return authService.login(request);
    }

    // POST /api/auth/refresh — trade a refresh token for a new token pair; public (rate-limited)
    @PostMapping("/refresh")
    public AuthDtos.TokenResponse refresh(@Valid @RequestBody AuthDtos.RefreshRequest request) {
        return authService.refresh(request);
    }

    // GET /api/auth/me — profile of the logged-in user; any authenticated user
    @GetMapping("/me")
    public UserDtos.UserResponse me() {
        return userRepository.findById(CurrentUser.get().id())
                .map(UserDtos.UserResponse::from)
                .orElseThrow();
    }
}
