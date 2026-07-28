package com.screenpilot.signage.security;

import com.screenpilot.signage.domain.User;
import com.screenpilot.signage.repo.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
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
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Authenticates portal users from the "Authorization: Bearer &lt;JWT&gt;" header.
 * A valid access token is turned into an {@link AppPrincipal} on the Spring
 * SecurityContext; anything invalid simply falls through unauthenticated.
 *
 * <p>Extends OncePerRequestFilter so this logic runs exactly once per HTTP request,
 * even when the servlet container re-dispatches internally (error pages, forwards,
 * async) — without that guarantee we could parse the JWT and query the DB twice.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    // jwtService checks token signatures/expiry; userRepository re-checks the account
    private final JwtService jwtService;
    private final UserRepository userRepository;

    /** Constructor injection — Spring supplies both collaborators. */
    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    /**
     * Story: read the Authorization header, validate the JWT, confirm the user still
     * exists and is active, then put an authenticated principal into the SecurityContext.
     * On any failure it silently does nothing — the authorization rules further down
     * the chain then reject the request with 401 where authentication was required.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // raw header format: "Bearer eyJhbGciOiJIUzI1NiJ9...."
        String header = request.getHeader("Authorization");
        // act only when (a) a Bearer header is present and (b) no earlier filter already
        // authenticated this request — "first successful filter wins" keeps this filter
        // and DeviceTokenFilter from fighting over the same request
        if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
            // strip the 7-character "Bearer " prefix to get the bare token
            String token = header.substring(7);
            try {
                // 1. verify the signature and expiry; only "access" tokens may authenticate requests
                Claims claims = jwtService.parse(token);
                // refresh tokens are valid JWTs too but live for days — if they could
                // call APIs directly, a stolen one would be a long-lived skeleton key,
                // so only the short-lived "access" type may authenticate requests
                if (JwtService.TYPE_ACCESS.equals(claims.get("type", String.class))) {
                    // 2. load the user fresh from the DB so deactivated accounts lose access immediately
                    //    (the token's claims are a snapshot from login time — a role change
                    //    or a ban must beat whatever the token still says about the user)
                    UUID userId = UUID.fromString(claims.getSubject());
                    User user = userRepository.findById(userId).orElse(null);
                    if (user != null && user.isActive()) {
                        // 3. attach the principal (with role + allowed group ids) to the security context
                        AppPrincipal principal = new AppPrincipal(
                                user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                                user.getGroups().stream().map(g -> g.getId()).collect(Collectors.toSet()));
                        // credentials are null — identity is already proven by the JWT
                        // signature; the "ROLE_..." authority is what hasRole(...) matches
                        var auth = new UsernamePasswordAuthenticationToken(
                                principal, null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
                        // from here on, the rest of this request sees the caller as logged in
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // invalid token -> proceed unauthenticated; entry point returns 401 where required
                // (deliberately swallowed: an expired or garbled token is an everyday
                // client condition, not a server fault — rethrowing would cause a 500)
            }
        }
        // always continue the chain — this filter only ever adds identity, never blocks
        chain.doFilter(request, response);
    }
}
