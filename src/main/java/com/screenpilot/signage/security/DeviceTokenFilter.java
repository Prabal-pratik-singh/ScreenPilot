package com.screenpilot.signage.security;

import com.screenpilot.signage.domain.Screen;
import com.screenpilot.signage.repo.ScreenRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates player devices by the X-Device-Token header issued at pairing time.
 *
 * <p>The device-side twin of {@link JwtAuthFilter}: TVs are not people, so instead of
 * short-lived JWTs each one holds a single long-lived random token granted when the
 * screen was paired. OncePerRequestFilter guarantees one DB lookup per request even
 * if the container re-dispatches internally.
 */
@Component
public class DeviceTokenFilter extends OncePerRequestFilter {

    // custom header the player sends on every call; kept separate from "Authorization"
    // so a device can never be mistaken for (or upgraded to) a portal user
    public static final String HEADER = "X-Device-Token";

    private final ScreenRepository screenRepository;

    /** Constructor injection of the screens repository used to look tokens up. */
    public DeviceTokenFilter(ScreenRepository screenRepository) {
        this.screenRepository = screenRepository;
    }

    /**
     * Story: read X-Device-Token, hash it, find the paired screen it belongs to, then
     * mark the request as that device with the single ROLE_DEVICE authority.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        // only act when the header is present and nothing else (e.g. a JWT) has already
        // authenticated this request — first successful filter wins, they never fight
        if (token != null && !token.isBlank() && SecurityContextHolder.getContext().getAuthentication() == null) {
            // devices send the plaintext; the DB holds only its SHA-256 hash
            // — hash the incoming value and look *that* up, so a stolen database dump
            // contains nothing that could be replayed to impersonate a screen
            Screen screen = screenRepository.findByDeviceToken(TokenHasher.sha256Hex(token)).orElse(null);
            // only screens that completed pairing may authenticate as a device
            // (unpairing in the portal clears that flag — the admin kill switch: the old
            // token stops working immediately even though the screen row still exists)
            if (screen != null && screen.isPaired()) {
                // the principal carries just id + name — all the player endpoints need
                DevicePrincipal principal = new DevicePrincipal(screen.getId(), screen.getName());
                // ROLE_DEVICE unlocks exactly the /api/player/** routes and nothing else,
                // so even a compromised TV cannot reach portal/admin APIs
                var auth = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        // continue regardless — unauthenticated player calls get rejected later by the
        // hasRole("DEVICE") security rule, not by this filter
        chain.doFilter(request, response);
    }
}
