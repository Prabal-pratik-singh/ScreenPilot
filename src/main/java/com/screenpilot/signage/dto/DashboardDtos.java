package com.screenpilot.signage.dto;

import java.util.List;

/**
 * DTOs (Data Transfer Objects) for the dashboard endpoints — read-only summaries the
 * home page renders. Each is a Java record; this outer class is only a namespace.
 */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    /** Sent for the top stat tiles: fleet-wide screen counts by health. */
    public record Stats(long total, long online, long offline, long offlineOver24h) {
    }

    /** Sent for the drill-down tree (state -> city -> store), each node with its own online/offline counts. */
    public record TreeNode(String key, String label, String level, long online, long offline,
                           List<TreeNode> children) {
    }
}
