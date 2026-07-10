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
 */
@Component
public class DeviceTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Device-Token";

    private final ScreenRepository screenRepository;

    public DeviceTokenFilter(ScreenRepository screenRepository) {
        this.screenRepository = screenRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token != null && !token.isBlank() && SecurityContextHolder.getContext().getAuthentication() == null) {
            Screen screen = screenRepository.findByDeviceToken(token).orElse(null);
            if (screen != null && screen.isPaired()) {
                DevicePrincipal principal = new DevicePrincipal(screen.getId(), screen.getName());
                var auth = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
