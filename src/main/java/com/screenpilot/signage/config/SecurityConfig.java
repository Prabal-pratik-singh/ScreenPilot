package com.screenpilot.signage.config;

import com.screenpilot.signage.security.DeviceTokenFilter;
import com.screenpilot.signage.security.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Central Spring Security setup: which URLs are public, which need a login, and how
 * requests are authenticated. This API is stateless — every request must carry either a
 * JWT (portal users) or a device token (TV players) in a header; there are no sessions
 * or cookies. {@code @EnableMethodSecurity} additionally allows {@code @PreAuthorize}
 * role checks directly on controller/service methods.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    // the two custom authentication filters (portal JWTs, device tokens) and the typed
    // app configuration (application.yml / env vars) — all provided by Spring
    private final JwtAuthFilter jwtAuthFilter;
    private final DeviceTokenFilter deviceTokenFilter;
    private final AppProperties props;

    /** Constructor injection: Spring supplies the beans; final fields make them mandatory. */
    public SecurityConfig(JwtAuthFilter jwtAuthFilter, DeviceTokenFilter deviceTokenFilter, AppProperties props) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.deviceTokenFilter = deviceTokenFilter;
        this.props = props;
    }

    /**
     * The main security rulebook, applied to every HTTP request.
     * CSRF protection is disabled because CSRF attacks rely on browser cookies/sessions;
     * this API authenticates via tokens in headers, which a hostile site cannot forge.
     * Login, health, pairing and WebSocket handshake endpoints stay public; media/screenshot
     * GETs are protected by signed URLs instead; player endpoints need the DEVICE role;
     * everything else requires an authenticated portal user.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // no CSRF protection needed: that attack relies on cookies the browser
                // attaches automatically; our tokens live in explicit headers that a
                // hostile site can never make the browser add
                .csrf(csrf -> csrf.disable())
                // apply the browser cross-origin rules from the CORS bean defined below
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // STATELESS: never create or read an HTTP session — every request must
                // re-prove its identity with a token, so there is no session to hijack
                // and any server instance could answer any request
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // the URL rulebook — evaluated top to bottom, first match wins
                .authorizeHttpRequests(auth -> auth
                        // login/refresh must be open: callers have no token yet
                        // (the chicken-and-egg of every token-based API)
                        .requestMatchers("/api/auth/**").permitAll()
                        // liveness probe for Docker/monitoring — reveals no secrets
                        .requestMatchers("/api/health").permitAll()
                        // pairing is how a factory-fresh TV *gets* its token, so it must
                        // work unauthenticated; RateLimitFilter throttles guessing there
                        .requestMatchers("/api/player/pair/**").permitAll()
                        // the WebSocket/SockJS handshake is plain HTTP that happens before
                        // any credentials can flow over STOMP, so the endpoint stays open
                        .requestMatchers("/ws/**").permitAll()
                        // binaries fetched by <img>/<video> tags and player downloads:
                        // no session possible there — HMAC-signed URLs are the access control
                        .requestMatchers(HttpMethod.GET, "/api/media/*/file", "/api/media/*/thumb").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/screens/*/screenshot").permitAll()
                        // everything the player calls (config, heartbeat, logs) needs the
                        // DEVICE role, which only DeviceTokenFilter ever grants
                        .requestMatchers("/api/player/**").hasRole("DEVICE")
                        // Spring re-dispatches failed requests to /error internally; locking
                        // it down would mask real errors behind confusing 401s
                        .requestMatchers("/error").permitAll()
                        // default-deny: anything not listed above requires a logged-in
                        // portal user — new endpoints are protected automatically
                        .anyRequest().authenticated())
                // the "entry point" runs when an unauthenticated request hits a protected
                // URL; instead of Spring's default HTML/redirect we answer a small JSON
                // 401 the React frontend can parse the same way as other API errors
                .exceptionHandling(eh -> eh.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Authentication required\",\"path\":\"" + req.getRequestURI() + "\"}");
                }))
                // slot both token filters into the chain ahead of Spring's classic
                // form-login filter; each one authenticates only if nothing else already
                // has, so JWT users and device tokens coexist peacefully on one API
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(deviceTokenFilter, UsernamePasswordAuthenticationFilter.class);
        // compile the configured rulebook into the actual filter-chain object
        return http.build();
    }

    /**
     * CORS (Cross-Origin Resource Sharing) rules: tells browsers which frontend origins
     * are allowed to call this API from JavaScript, with which methods and headers.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        // start from an empty config and list exactly what browsers may do cross-origin
        CorsConfiguration config = new CorsConfiguration();
        // patterns (supports "*") — auth is header-token based, no cookies, so a
        // permissive CORS default is safe and survives tunnels/domains/LAN IPs
        // (unlike setAllowedOrigins, the *patterns* variant may combine "*" with
        // credentials: the server echoes the caller's own origin back instead of a
        // literal "*", which browsers would reject)
        config.setAllowedOriginPatterns(props.getCors().getAllowedOrigins());
        // every verb the portal uses; the browser consults this list during its
        // OPTIONS "preflight" check before letting JavaScript send the real request
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // accept any request header — notably Authorization (JWT) and X-Device-Token,
        // which are non-standard and would otherwise fail the preflight
        config.setAllowedHeaders(List.of("*"));
        // JS may normally read only a few response headers; exposing Content-Disposition
        // lets the frontend see the suggested filename on media downloads
        config.setExposedHeaders(List.of("Content-Disposition"));
        // allow requests that carry credentials alongside the header tokens
        config.setAllowCredentials(true);
        // register this single rule set for every path in the application
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** BCrypt password hashing — passwords are stored as slow, salted hashes, never plaintext. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // default strength 10 = 2^10 internal rounds; ~100ms per check is nothing for
        // one login but ruinous for an attacker trying millions of guesses
        return new BCryptPasswordEncoder();
    }

    /**
     * Role hierarchy: a higher role automatically inherits every permission of the roles
     * below it (SUPER_ADMIN > ADMIN > CONTENT_MANAGER > VIEWER), so a check like
     * hasRole('VIEWER') also passes for admins without listing every role.
     *
     * <p>Declared {@code static} so Spring can create it before this config class is
     * fully built — the method-security machinery asks for it very early at startup.
     */
    @Bean
    static RoleHierarchy roleHierarchy() {
        // one rule per line, read as "the left role can do everything the right can";
        // Spring chains the rules, so SUPER_ADMIN transitively includes VIEWER
        return RoleHierarchyImpl.fromHierarchy("""
                ROLE_SUPER_ADMIN > ROLE_ADMIN
                ROLE_ADMIN > ROLE_CONTENT_MANAGER
                ROLE_CONTENT_MANAGER > ROLE_VIEWER
                """);
    }

    /** Makes {@code @PreAuthorize} expressions on methods respect the role hierarchy above. */
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        // take the standard expression evaluator and swap in our hierarchy so a
        // @PreAuthorize("hasRole('VIEWER')") check also passes for an ADMIN
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
    }
}
