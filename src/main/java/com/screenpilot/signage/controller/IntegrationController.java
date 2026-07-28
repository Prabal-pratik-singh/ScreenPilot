package com.screenpilot.signage.web;

import com.screenpilot.signage.integrations.ContentSourceProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Lists the pluggable content-source integrations (ContentSourceProvider
 * beans) and whether each one is enabled/configured on this deployment.
 */
@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {

    private final List<ContentSourceProvider> providers;

    public IntegrationController(List<ContentSourceProvider> providers) {
        this.providers = providers;
    }

    // GET /api/integrations — describe each provider and its enabled state; ADMIN only
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> list() {
        return providers.stream()
                .map(p -> Map.<String, Object>of(
                        "id", p.id(),
                        "displayName", p.displayName(),
                        "description", p.description(),
                        "enabled", p.isEnabled(),
                        "requirement", p.requirement()))
                .toList();
    }
}
