package com.screenpilot.signage.web;

import com.screenpilot.signage.repo.ScreenGroupRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness/readiness probe for Docker healthchecks and uptime monitors.
 * Answers 200 only when the app AND its database connection actually work —
 * "the lights are on and somebody is home".
 */
@RestController
public class HealthController {

    private final ScreenGroupRepository groupRepository;

    public HealthController(ScreenGroupRepository groupRepository) {
        this.groupRepository = groupRepository;
    }

    // GET /api/health — public, no auth: 200 {"status":"UP"} or 503 when the DB is unreachable
    @GetMapping("/api/health")
    public ResponseEntity<Map<String, String>> health() {
        try {
            groupRepository.count(); // cheap DB round-trip
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("status", "DOWN", "db", "unreachable"));
        }
    }
}
