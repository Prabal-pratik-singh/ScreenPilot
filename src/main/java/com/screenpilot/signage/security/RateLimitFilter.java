package com.screenpilot.signage.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window rate limiter — a bouncer on the endpoints bots
 * love: login (password guessing) and pairing (code guessing). Per-IP,
 * per-endpoint; answers HTTP 429 when the window is exceeded. No external
 * store needed at this fleet size.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // before the security chain
public class RateLimitFilter extends OncePerRequestFilter {

    private record Rule(String method, String prefix, int maxPerMinute) {
    }

    private static final Rule[] RULES = {
            new Rule("POST", "/api/auth/login", 10),
            new Rule("POST", "/api/auth/refresh", 30),
            new Rule("POST", "/api/player/pair/request", 10),
            new Rule("GET", "/api/player/pair/poll/", 100),
    };

    private static final long WINDOW_MS = 60_000;

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Rule rule = match(request);
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }
        String key = clientIp(request) + "|" + rule.prefix();
        long now = System.currentTimeMillis();
        Deque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        boolean allowed;
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS) {
                window.pollFirst();
            }
            allowed = window.size() < rule.maxPerMinute();
            if (allowed) {
                window.addLast(now);
            }
        }
        if (!allowed) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\","
                    + "\"message\":\"Too many attempts — wait a minute and try again\","
                    + "\"path\":\"" + request.getRequestURI() + "\"}");
            return;
        }
        chain.doFilter(request, response);

        // opportunistic cleanup so the map never grows unbounded
        if (hits.size() > 10_000) {
            hits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty()
                            || now - e.getValue().peekLast() > WINDOW_MS * 5;
                }
            });
        }
    }

    private Rule match(HttpServletRequest request) {
        for (Rule rule : RULES) {
            if (rule.method().equals(request.getMethod()) && request.getRequestURI().startsWith(rule.prefix())) {
                return rule;
            }
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        // behind our nginx proxy the real client is in X-Forwarded-For
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
