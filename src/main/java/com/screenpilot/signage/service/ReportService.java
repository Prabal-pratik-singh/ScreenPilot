package com.screenpilot.signage.service;

import com.screenpilot.signage.domain.PlaybackLog;
import com.screenpilot.signage.domain.Screen;
import com.screenpilot.signage.domain.ScreenStatusEvent;
import com.screenpilot.signage.error.ApiException;
import com.screenpilot.signage.repo.PlaybackLogRepository;
import com.screenpilot.signage.repo.ScreenStatusEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportService {

    public record ProofOfPlayRow(String creative, String itemType, UUID mediaId, UUID screenId, String screenName,
                                 long playCount, double totalSeconds, Instant firstPlayed, Instant lastPlayed) {
    }

    public record SeriesPoint(String label, double value) {
    }

    public record ProofOfPlayReport(List<ProofOfPlayRow> rows, long totalPlays, double totalSeconds,
                                    List<SeriesPoint> playsPerDay) {
    }

    public record UptimeDay(String date, double pct) {
    }

    public record UptimeRow(UUID screenId, String screenName, String storeName, String city,
                            double avgPct, List<UptimeDay> days) {
    }

    public record UptimeReport(List<UptimeRow> rows, List<SeriesPoint> onlineOverTime,
                               List<UptimeRow> redFlags) {
    }

    private final PlaybackLogRepository playbackLogs;
    private final ScreenStatusEventRepository statusEvents;
    private final ScreenService screenService;

    public ReportService(PlaybackLogRepository playbackLogs, ScreenStatusEventRepository statusEvents,
                         ScreenService screenService) {
        this.playbackLogs = playbackLogs;
        this.statusEvents = statusEvents;
        this.screenService = screenService;
    }

    private Instant startOfDayIST(LocalDate date) {
        return date.atStartOfDay(TimeUtil.IST).toInstant();
    }

    // -------------------------------------------------- proof of play

    @Transactional(readOnly = true)
    public ProofOfPlayReport proofOfPlay(LocalDate from, LocalDate to, List<UUID> screenIds, List<UUID> mediaIds) {
        validateRange(from, to);
        Map<UUID, Screen> accessible = screenService.accessibleScreens().stream()
                .collect(Collectors.toMap(Screen::getId, s -> s));
        List<UUID> scope = (screenIds == null || screenIds.isEmpty())
                ? new ArrayList<>(accessible.keySet())
                : screenIds.stream().filter(accessible::containsKey).toList();
        if (scope.isEmpty()) {
            return new ProofOfPlayReport(List.of(), 0, 0, List.of());
        }

        Instant fromInstant = startOfDayIST(from);
        Instant toInstant = startOfDayIST(to.plusDays(1));
        List<PlaybackLog> logs = playbackLogs.findInRange(fromInstant, toInstant, scope).stream()
                .filter(l -> mediaIds == null || mediaIds.isEmpty() || (l.getMediaId() != null && mediaIds.contains(l.getMediaId())))
                .toList();

        record Key(String creative, UUID mediaId, UUID screenId) {
        }
        Map<Key, List<PlaybackLog>> grouped = new LinkedHashMap<>();
        for (PlaybackLog l : logs) {
            String creative = l.getItemTitle() == null ? "(untitled)" : l.getItemTitle();
            grouped.computeIfAbsent(new Key(creative, l.getMediaId(), l.getScreenId()), k -> new ArrayList<>()).add(l);
        }

        List<ProofOfPlayRow> rows = grouped.entrySet().stream().map(e -> {
            List<PlaybackLog> group = e.getValue();
            Screen screen = accessible.get(e.getKey().screenId());
            return new ProofOfPlayRow(
                    e.getKey().creative(),
                    group.get(0).getItemType(),
                    e.getKey().mediaId(),
                    e.getKey().screenId(),
                    screen == null ? "(removed screen)" : screen.getName(),
                    group.size(),
                    Math.round(group.stream().mapToDouble(PlaybackLog::getDurationSeconds).sum() * 10) / 10.0,
                    group.stream().map(PlaybackLog::getStartedAt).min(Instant::compareTo).orElse(null),
                    group.stream().map(PlaybackLog::getStartedAt).max(Instant::compareTo).orElse(null));
        }).sorted(Comparator.comparingLong(ProofOfPlayRow::playCount).reversed()).toList();

        // plays per IST day for the bar chart
        Map<String, Long> perDay = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            perDay.put(d.toString(), 0L);
        }
        for (PlaybackLog l : logs) {
            String day = ZonedDateTime.ofInstant(l.getStartedAt(), TimeUtil.IST).toLocalDate().toString();
            perDay.computeIfPresent(day, (k, v) -> v + 1);
        }

        return new ProofOfPlayReport(
                rows,
                logs.size(),
                Math.round(logs.stream().mapToDouble(PlaybackLog::getDurationSeconds).sum() * 10) / 10.0,
                perDay.entrySet().stream().map(e -> new SeriesPoint(e.getKey(), e.getValue())).toList());
    }

    // -------------------------------------------------- uptime

    @Transactional(readOnly = true)
    public UptimeReport uptime(LocalDate from, LocalDate to) {
        validateRange(from, to);
        List<Screen> screens = screenService.accessibleScreens();
        if (screens.isEmpty()) {
            return new UptimeReport(List.of(), List.of(), List.of());
        }
        List<UUID> ids = screens.stream().map(Screen::getId).toList();
        Instant rangeEnd = min(Instant.now(), startOfDayIST(to.plusDays(1)));
        List<ScreenStatusEvent> events = statusEvents.findBefore(ids, rangeEnd);
        Map<UUID, List<ScreenStatusEvent>> byScreen = events.stream()
                .collect(Collectors.groupingBy(ScreenStatusEvent::getScreenId));

        List<UptimeRow> rows = new ArrayList<>();
        for (Screen screen : screens) {
            List<ScreenStatusEvent> evts = byScreen.getOrDefault(screen.getId(), List.of());
            List<UptimeDay> days = new ArrayList<>();
            double sum = 0;
            int counted = 0;
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                Instant dayStart = startOfDayIST(d);
                Instant dayEnd = min(startOfDayIST(d.plusDays(1)), Instant.now());
                if (!dayEnd.isAfter(dayStart)) {
                    days.add(new UptimeDay(d.toString(), 0));
                    continue; // future day
                }
                double online = onlineSecondsInWindow(evts, dayStart, dayEnd, screen);
                double pct = Math.round(online / Duration.between(dayStart, dayEnd).getSeconds() * 1000) / 10.0;
                days.add(new UptimeDay(d.toString(), pct));
                sum += pct;
                counted++;
            }
            rows.add(new UptimeRow(screen.getId(), screen.getName(), screen.getStoreName(), screen.getCity(),
                    counted == 0 ? 0 : Math.round(sum / counted * 10) / 10.0, days));
        }
        rows.sort(Comparator.comparingDouble(UptimeRow::avgPct));

        // hourly "screens online" line
        List<SeriesPoint> onlineOverTime = new ArrayList<>();
        Instant cursor = startOfDayIST(from);
        while (!cursor.isAfter(rangeEnd)) {
            final Instant at = cursor;
            long count = screens.stream().filter(s -> isOnlineAt(byScreen.getOrDefault(s.getId(), List.of()), at, s)).count();
            String label = ZonedDateTime.ofInstant(at, TimeUtil.IST).toLocalDateTime().toString().replace('T', ' ').substring(5, 14) + "h";
            onlineOverTime.add(new SeriesPoint(label, count));
            cursor = cursor.plus(Duration.ofHours(Duration.between(startOfDayIST(from), rangeEnd).toDays() >= 4 ? 6 : 1));
        }

        List<UptimeRow> redFlags = rows.stream().filter(r -> r.avgPct() < 90).limit(8).toList();
        return new UptimeReport(rows, onlineOverTime, redFlags);
    }

    /** Walks the event history to sum online time inside [start, end). */
    private double onlineSecondsInWindow(List<ScreenStatusEvent> events, Instant start, Instant end, Screen screen) {
        boolean online = statusAt(events, start, screen);
        Instant cursor = start;
        double seconds = 0;
        for (ScreenStatusEvent e : events) {
            if (!e.getAt().isAfter(start)) continue;
            if (!e.getAt().isBefore(end)) break;
            if (online) {
                seconds += Duration.between(cursor, e.getAt()).getSeconds();
            }
            online = e.getStatus() == Screen.Status.ONLINE;
            cursor = e.getAt();
        }
        if (online) {
            seconds += Duration.between(cursor, end).getSeconds();
        }
        return seconds;
    }

    private boolean isOnlineAt(List<ScreenStatusEvent> events, Instant at, Screen screen) {
        return statusAt(events, at, screen);
    }

    private boolean statusAt(List<ScreenStatusEvent> events, Instant at, Screen screen) {
        ScreenStatusEvent last = null;
        for (ScreenStatusEvent e : events) {
            if (e.getAt().isAfter(at)) break;
            last = e;
        }
        if (last != null) {
            return last.getStatus() == Screen.Status.ONLINE;
        }
        // no history before this point — screens start offline
        return false;
    }

    private Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw ApiException.badRequest("from and to dates are required (YYYY-MM-DD)");
        }
        if (to.isBefore(from)) {
            throw ApiException.badRequest("to date is before from date");
        }
        if (Duration.between(startOfDayIST(from), startOfDayIST(to)).toDays() > 92) {
            throw ApiException.badRequest("Date range too large (max 92 days)");
        }
    }
}
