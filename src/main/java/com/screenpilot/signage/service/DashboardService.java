package com.screenpilot.signage.service;

import com.screenpilot.signage.domain.Screen;
import com.screenpilot.signage.dto.DashboardDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only numbers for the portal home page: fleet-wide online/offline
 * counters and a State -> City -> Store tree of screens. Everything is scoped
 * to the screens the current user is allowed to see.
 */
@Service
public class DashboardService {

    private final ScreenService screenService;

    public DashboardService(ScreenService screenService) {
        this.screenService = screenService;
    }

    /** Counts total / online / offline screens, plus those silent for over 24 hours. */
    @Transactional(readOnly = true)
    public DashboardDtos.Stats stats() {
        List<Screen> screens = screenService.accessibleScreens();
        Instant dayAgo = Instant.now().minus(Duration.ofHours(24));
        long online = screens.stream().filter(s -> s.getStatus() == Screen.Status.ONLINE).count();
        long offline = screens.size() - online;
        long offlineOver24h = screens.stream()
                .filter(s -> s.getStatus() == Screen.Status.OFFLINE)
                .filter(s -> s.getLastHeartbeatAt() == null || s.getLastHeartbeatAt().isBefore(dayAgo))
                .count();
        return new DashboardDtos.Stats(screens.size(), online, offline, offlineOver24h);
    }

    /** Builds the State -> City -> Store tree with online/offline counts on every node. */
    @Transactional(readOnly = true)
    public List<DashboardDtos.TreeNode> groupTree() {
        List<Screen> screens = screenService.accessibleScreens();

        // 1. bucket screens by state, then city, then store (sorted for a stable tree)
        Map<String, Map<String, Map<String, List<Screen>>>> byState = new LinkedHashMap<>();
        screens.stream()
                .sorted(Comparator
                        .comparing((Screen s) -> nullSafe(s.getState()))
                        .thenComparing(s -> nullSafe(s.getCity()))
                        .thenComparing(s -> nullSafe(s.getStoreName())))
                .forEach(s -> byState
                        .computeIfAbsent(nullSafe(s.getState()), k -> new LinkedHashMap<>())
                        .computeIfAbsent(nullSafe(s.getCity()), k -> new LinkedHashMap<>())
                        .computeIfAbsent(nullSafe(s.getStoreName()), k -> new ArrayList<>())
                        .add(s));

        // 2. turn the nested map into TreeNodes, aggregating counts bottom-up
        List<DashboardDtos.TreeNode> stateNodes = new ArrayList<>();
        byState.forEach((state, cities) -> {
            List<DashboardDtos.TreeNode> cityNodes = new ArrayList<>();
            cities.forEach((city, stores) -> {
                List<DashboardDtos.TreeNode> storeNodes = new ArrayList<>();
                stores.forEach((store, storeScreens) -> storeNodes.add(node(
                        state + "/" + city + "/" + store, store, "STORE", storeScreens, List.of())));
                List<Screen> cityScreens = stores.values().stream().flatMap(List::stream).toList();
                cityNodes.add(node(state + "/" + city, city, "CITY", cityScreens, storeNodes));
            });
            List<Screen> stateScreens = cities.values().stream()
                    .flatMap(m -> m.values().stream()).flatMap(List::stream).toList();
            stateNodes.add(node(state, state, "STATE", stateScreens, cityNodes));
        });
        return stateNodes;
    }

    // builds one tree node with its online/offline tally
    private DashboardDtos.TreeNode node(String key, String label, String level, List<Screen> screens,
                                        List<DashboardDtos.TreeNode> children) {
        long online = screens.stream().filter(s -> s.getStatus() == Screen.Status.ONLINE).count();
        return new DashboardDtos.TreeNode(key, label, level, online, screens.size() - online, children);
    }

    private String nullSafe(String v) {
        return v == null || v.isBlank() ? "Unassigned" : v;
    }
}
