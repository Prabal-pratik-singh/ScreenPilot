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
 *
 * <p>Registered as a plain servlet filter at (nearly) highest precedence, i.e. it runs
 * before the whole Spring Security chain — floods are bounced with a cheap in-memory
 * check before any JWT parsing, BCrypt hashing or database query can burn CPU.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // before the security chain
public class RateLimitFilter extends OncePerRequestFilter {

    // one throttle rule: HTTP method + URL prefix + allowed hits per minute
    // (a record is a small immutable data class — constructor/accessors generated)
    private record Rule(String method, String prefix, int maxPerMinute) {
    }

    // the protected endpoints, and the attack each cap is sized against:
    private static final Rule[] RULES = {
            // password brute-force: 10 tries/min/IP makes dictionary attacks hopeless
            new Rule("POST", "/api/auth/login", 10),
            // token grinding: real clients refresh occasionally, bots hammer it
            new Rule("POST", "/api/auth/refresh", 30),
            // stops mass-generation of pairing codes
            new Rule("POST", "/api/player/pair/request", 10),
            // TVs legitimately poll every few seconds while waiting to be claimed, so
            // this cap is generous — it only stops outright floods / code scanning
            new Rule("GET", "/api/player/pair/poll/", 100),
    };

    // window length: "per minute" is measured over the trailing 60 seconds
    private static final long WINDOW_MS = 60_000;

    // key = "clientIp|endpointPrefix" -> timestamps (ms) of that caller's recent hits;
    // ConcurrentHashMap because many request threads read/write it at the same time
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /**
     * Story: match the request to a rule, fetch this caller's timestamp window, evict
     * entries older than 60s, then either record the hit and continue or answer 429;
     * finally, occasionally garbage-collect the map.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 1. only rate-limit requests that match one of the protected endpoints
        Rule rule = match(request);
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }
        // 2. one timestamp deque per (client IP, endpoint) pair = the sliding window
        String key = clientIp(request) + "|" + rule.prefix();
        long now = System.currentTimeMillis();
        // computeIfAbsent creates the deque atomically on this key's very first hit
        Deque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        boolean allowed;
        // ArrayDeque is not thread-safe — parallel requests from the same caller must
        // not mutate one window at once, so lock just this window (never the whole map)
        synchronized (window) {
            // 3. drop timestamps older than 60s so only the last minute counts
            //    (this is what makes the window "slide": unlike fixed one-minute buckets,
            //    a caller can never squeeze 2x the limit in around a bucket boundary)
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MS) {
                window.pollFirst();
            }
            // 4. allow if under the per-minute cap, and record this hit
            allowed = window.size() < rule.maxPerMinute();
            if (allowed) {
                window.addLast(now);
            }
        }
        // 5. over the cap -> reply 429 with a friendly JSON body and stop the chain
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
        // (every unique IP+endpoint adds a key that would otherwise live forever; past
        // 10k keys, sweep windows that are empty or idle for 5+ minutes — rare & cheap)
        if (hits.size() > 10_000) {
            hits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty()
                            || now - e.getValue().peekLast() > WINDOW_MS * 5;
                }
            });
        }
    }

    /** Returns the first rule whose method and URL prefix match this request, else null. */
    private Rule match(HttpServletRequest request) {
        for (Rule rule : RULES) {
            // prefix match so /api/player/pair/poll/ABC123 falls under the poll rule
            if (rule.method().equals(request.getMethod()) && request.getRequestURI().startsWith(rule.prefix())) {
                return rule;
            }
        }
        return null;
    }

    /** Best-effort real client address, seen through the reverse proxy. */
    private String clientIp(HttpServletRequest request) {
        // behind our nginx proxy the real client is in X-Forwarded-For
        // (getRemoteAddr() would be the proxy itself — all users would then share one
        // bucket, and a single attacker could rate-limit everyone else out)
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // the header can be a chain "client, proxy1, proxy2" — the first entry is
            // the original caller; each hop appends the address it saw
            return forwarded.split(",")[0].trim();
        }
        // no proxy in front (bare local run): the socket peer is the client itself
        return request.getRemoteAddr();
    }
}
