package com.screenpilot.signage.controller;

import com.screenpilot.signage.dto.DashboardDtos;
import com.screenpilot.signage.service.DashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoints for the portal home page numbers. @PreAuthorize at class
 * level means every endpoint requires at least the VIEWER role.
 */
@RestController
@RequestMapping("/api/dashboard")
@PreAuthorize("hasRole('VIEWER')")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    // GET /api/dashboard/stats — fleet counters (total/online/offline); any logged-in user
    @GetMapping("/stats")
    public DashboardDtos.Stats stats() {
        return dashboardService.stats();
    }

    // GET /api/dashboard/tree — State > City > Store screen tree; any logged-in user
    @GetMapping("/tree")
    public List<DashboardDtos.TreeNode> tree() {
        return dashboardService.groupTree();
    }
}
