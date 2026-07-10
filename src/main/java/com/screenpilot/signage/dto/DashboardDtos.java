package com.screenpilot.signage.dto;

import java.util.List;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record Stats(long total, long online, long offline, long offlineOver24h) {
    }

    public record TreeNode(String key, String label, String level, long online, long offline,
                           List<TreeNode> children) {
    }
}
