package com.screenpilot.signage.web;

import com.screenpilot.signage.dto.DashboardDtos;
import com.screenpilot.signage.service.DashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@PreAuthorize("hasRole('VIEWER')")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/stats")
    public DashboardDtos.Stats stats() {
        return dashboardService.stats();
    }

    @GetMapping("/tree")
    public List<DashboardDtos.TreeNode> tree() {
        return dashboardService.groupTree();
    }
}
