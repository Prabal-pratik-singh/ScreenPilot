package com.screenpilot.signage.service;

import com.screenpilot.signage.domain.Schedule;
import com.screenpilot.signage.domain.Screen;
import com.screenpilot.signage.domain.User;
import com.screenpilot.signage.dto.ScheduleDtos;
import com.screenpilot.signage.error.ApiException;
import com.screenpilot.signage.repo.PlaylistRepository;
import com.screenpilot.signage.repo.ScheduleRepository;
import com.screenpilot.signage.repo.UserRepository;
import com.screenpilot.signage.security.AppPrincipal;
import com.screenpilot.signage.security.CurrentUser;
import com.screenpilot.signage.ws.ContentPushService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final PlaylistRepository playlistRepository;
    private final com.screenpilot.signage.repo.LayoutRepository layoutRepository;
    private final UserRepository userRepository;
    private final ScreenService screenService;
    private final ContentPushService contentPush;

    public ScheduleService(ScheduleRepository scheduleRepository, PlaylistRepository playlistRepository,
                           com.screenpilot.signage.repo.LayoutRepository layoutRepository,
                           UserRepository userRepository, ScreenService screenService,
                           ContentPushService contentPush) {
        this.scheduleRepository = scheduleRepository;
        this.playlistRepository = playlistRepository;
        this.layoutRepository = layoutRepository;
        this.userRepository = userRepository;
        this.screenService = screenService;
        this.contentPush = contentPush;
    }

    // ---------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public List<ScheduleDtos.ScheduleResponse> list() {
        AppPrincipal user = CurrentUser.get();
        return scheduleRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(s -> user.unrestricted() || s.getScreens().stream().anyMatch(
                        scr -> scr.getGroup() != null && user.groupIds().contains(scr.getGroup().getId())))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ScheduleDtos.ScheduleResponse get(UUID id) {
        return toDto(getEntity(id));
    }

    private Schedule getEntity(UUID id) {
        return scheduleRepository.findWithScreensById(id)
                .orElseThrow(() -> ApiException.notFound("Schedule not found"));
    }

    // ---------------------------------------------------------- mutations

    @Transactional
    public ScheduleDtos.ScheduleResponse create(ScheduleDtos.SaveScheduleRequest req) {
        Schedule schedule = new Schedule(req.name().trim(), req.contentType());
        apply(schedule, req);
        schedule.setCreatedBy(userRepository.findById(CurrentUser.get().id()).orElse(null));
        Set<UUID> affected = applyOverridesAndCollect(req, schedule);
        scheduleRepository.save(schedule);
        contentPush.schedulesUpdated(affected);
        return toDto(schedule);
    }

    @Transactional
    public ScheduleDtos.ScheduleResponse update(UUID id, ScheduleDtos.SaveScheduleRequest req) {
        Schedule schedule = getEntity(id);
        Set<UUID> before = schedule.getScreens().stream().map(Screen::getId).collect(Collectors.toSet());
        schedule.setName(req.name().trim());
        schedule.setContentType(req.contentType());
        apply(schedule, req);
        schedule.setUpdatedAt(Instant.now());
        Set<UUID> affected = applyOverridesAndCollect(req, schedule);
        affected.addAll(before);
        scheduleRepository.save(schedule);
        contentPush.schedulesUpdated(affected);
        return toDto(schedule);
    }

    @Transactional
    public void setActive(UUID id, boolean active) {
        Schedule schedule = getEntity(id);
        schedule.setActive(active);
        schedule.setUpdatedAt(Instant.now());
        scheduleRepository.save(schedule);
        contentPush.schedulesUpdated(schedule.getScreens().stream().map(Screen::getId).collect(Collectors.toSet()));
    }

    @Transactional
    public void delete(UUID id) {
        Schedule schedule = getEntity(id);
        Set<UUID> screens = schedule.getScreens().stream().map(Screen::getId).collect(Collectors.toSet());
        scheduleRepository.delete(schedule);
        scheduleRepository.flush();
        contentPush.schedulesUpdated(screens);
    }

    // ---------------------------------------------------------- conflicts

    @Transactional(readOnly = true)
    public ScheduleDtos.ConflictResponse previewConflicts(ScheduleDtos.SaveScheduleRequest req, UUID excludeId) {
        Schedule candidate = new Schedule(req.name() == null ? "New schedule" : req.name(), req.contentType());
        apply(candidate, req);

        List<UUID> screenIds = req.screenIds();
        Map<UUID, ScheduleDtos.ConflictInfo> conflicts = new LinkedHashMap<>();
        for (Schedule existing : scheduleRepository.findActiveForScreens(screenIds)) {
            if (excludeId != null && existing.getId().equals(excludeId)) {
                continue;
            }
            if (!isExpired(existing) && sameSpecificityOverlap(candidate, existing)) {
                List<ScheduleDtos.ScreenRef> shared = existing.getScreens().stream()
                        .filter(s -> screenIds.contains(s.getId()))
                        .map(this::screenRef)
                        .toList();
                if (!shared.isEmpty()) {
                    conflicts.put(existing.getId(), new ScheduleDtos.ConflictInfo(
                            existing.getId(), existing.getName(), describeWindow(existing), shared));
                }
            }
        }
        return new ScheduleDtos.ConflictResponse(new ArrayList<>(conflicts.values()));
    }

    /**
     * Conflicts are same-specificity overlaps: timed-vs-timed or allday-vs-allday.
     * A timed window over an all-day schedule is intentional layering — the timed
     * window simply wins during its hours (priority rule) — so it is not reported.
     */
    private boolean sameSpecificityOverlap(Schedule a, Schedule b) {
        if (a.isAllDay() != b.isAllDay()) {
            return false;
        }
        return dateRangesIntersect(a, b) && daysIntersect(a, b) && windowsIntersect(a, b);
    }

    private boolean dateRangesIntersect(Schedule a, Schedule b) {
        LocalDate aFrom = a.getDateFrom() == null ? LocalDate.MIN : a.getDateFrom();
        LocalDate aTo = a.getDateTo() == null ? LocalDate.MAX : a.getDateTo();
        LocalDate bFrom = b.getDateFrom() == null ? LocalDate.MIN : b.getDateFrom();
        LocalDate bTo = b.getDateTo() == null ? LocalDate.MAX : b.getDateTo();
        return !aFrom.isAfter(bTo) && !bFrom.isAfter(aTo);
    }

    private boolean daysIntersect(Schedule a, Schedule b) {
        List<String> daysA = TimeUtil.parseDays(a.getDaysOfWeek());
        List<String> daysB = TimeUtil.parseDays(b.getDaysOfWeek());
        if (daysA == null || daysB == null) {
            return true;
        }
        return daysA.stream().anyMatch(daysB::contains);
    }

    private boolean windowsIntersect(Schedule a, Schedule b) {
        if (a.isAllDay() || b.isAllDay()) {
            return true;
        }
        if (a.getStartTime() == null || b.getStartTime() == null) {
            return true;
        }
        // treat each window as one or two same-day intervals (overnight wraps)
        for (int[] ia : intervals(a)) {
            for (int[] ib : intervals(b)) {
                if (ia[0] < ib[1] && ib[0] < ia[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<int[]> intervals(Schedule s) {
        int start = s.getStartTime().toSecondOfDay();
        int end = s.getEndTime().toSecondOfDay();
        if (start < end) {
            return List.of(new int[]{start, end});
        }
        return List.of(new int[]{start, 86400}, new int[]{0, end});
    }

    // ---------------------------------------------------------- helpers

    private Set<UUID> applyOverridesAndCollect(ScheduleDtos.SaveScheduleRequest req, Schedule schedule) {
        Set<UUID> affected = schedule.getScreens().stream().map(Screen::getId).collect(Collectors.toSet());
        if (req.overrideScheduleIds() != null) {
            for (UUID overrideId : req.overrideScheduleIds()) {
                scheduleRepository.findWithScreensById(overrideId).ifPresent(existing -> {
                    existing.setActive(false);
                    existing.setUpdatedAt(Instant.now());
                    scheduleRepository.save(existing);
                    existing.getScreens().forEach(s -> affected.add(s.getId()));
                });
            }
        }
        return affected;
    }

    private void apply(Schedule schedule, ScheduleDtos.SaveScheduleRequest req) {
        if (req.contentType() == Schedule.ContentType.PLAYLIST) {
            if (req.playlistId() == null) {
                throw ApiException.badRequest("playlistId is required for playlist schedules");
            }
            schedule.setPlaylist(playlistRepository.findById(req.playlistId())
                    .orElseThrow(() -> ApiException.badRequest("Playlist not found")));
            schedule.setLayoutId(null);
        } else {
            if (req.layoutId() == null) {
                throw ApiException.badRequest("layoutId is required for layout schedules");
            }
            if (!layoutRepository.existsById(req.layoutId())) {
                throw ApiException.badRequest("Layout not found");
            }
            schedule.setLayoutId(req.layoutId());
            schedule.setPlaylist(null);
        }

        schedule.setAllDay(req.allDay());
        if (req.allDay()) {
            schedule.setStartTime(null);
            schedule.setEndTime(null);
            schedule.setPriority(0);
        } else {
            schedule.setStartTime(parseTime(req.startTime(), "startTime"));
            schedule.setEndTime(parseTime(req.endTime(), "endTime"));
            if (schedule.getStartTime().equals(schedule.getEndTime())) {
                throw ApiException.badRequest("Start and end time cannot be the same");
            }
            schedule.setPriority(10); // timed windows beat all-day schedules
        }
        if (req.daysOfWeek() != null && !req.daysOfWeek().isEmpty() && req.daysOfWeek().size() < 7) {
            List<String> valid = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
            List<String> days = req.daysOfWeek().stream().map(d -> d.trim().toUpperCase()).distinct().toList();
            if (!valid.containsAll(days)) {
                throw ApiException.badRequest("daysOfWeek must be MON..SUN");
            }
            schedule.setDaysOfWeek(String.join(",", days));
        } else {
            schedule.setDaysOfWeek(null);
        }
        if (req.dateFrom() != null && req.dateTo() != null && req.dateTo().isBefore(req.dateFrom())) {
            throw ApiException.badRequest("End date is before start date");
        }
        schedule.setDateFrom(req.dateFrom());
        schedule.setDateTo(req.dateTo());

        // Screens: verify access and existence
        Set<Screen> screens = new HashSet<>();
        for (UUID screenId : new HashSet<>(req.screenIds())) {
            screens.add(screenService.getAccessible(screenId));
        }
        schedule.setScreens(screens);
    }

    private LocalTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " is required for timed schedules");
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be HH:mm");
        }
    }

    private boolean isExpired(Schedule s) {
        return s.getDateTo() != null && s.getDateTo().isBefore(TimeUtil.todayIST());
    }

    private String statusOf(Schedule s) {
        if (isExpired(s)) return "EXPIRED";
        if (!s.isActive()) return "PAUSED";
        if (s.getDateFrom() != null && s.getDateFrom().isAfter(TimeUtil.todayIST())) return "UPCOMING";
        return "ACTIVE";
    }

    private String describeWindow(Schedule s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.isAllDay() ? "All day" : s.getStartTime() + "–" + s.getEndTime() + " IST");
        List<String> days = TimeUtil.parseDays(s.getDaysOfWeek());
        if (days != null) {
            sb.append(", ").append(String.join(" ", days));
        }
        if (s.getDateFrom() != null || s.getDateTo() != null) {
            sb.append(", ").append(s.getDateFrom() == null ? "…" : s.getDateFrom())
                    .append(" → ").append(s.getDateTo() == null ? "…" : s.getDateTo());
        }
        return sb.toString();
    }

    private ScheduleDtos.ScreenRef screenRef(Screen s) {
        return new ScheduleDtos.ScreenRef(s.getId(), s.getName(), s.getStoreName(), s.getCity(), s.getState());
    }

    private ScheduleDtos.ScheduleResponse toDto(Schedule s) {
        String layoutName = s.getLayoutId() == null ? null
                : layoutRepository.findById(s.getLayoutId()).map(l -> l.getName()).orElse(null);
        return new ScheduleDtos.ScheduleResponse(
                s.getId(), s.getName(), s.getContentType(),
                s.getPlaylist() == null ? null : s.getPlaylist().getId(),
                s.getPlaylist() == null ? null : s.getPlaylist().getName(),
                s.getLayoutId(), layoutName,
                s.isAllDay(), s.getStartTime(), s.getEndTime(),
                TimeUtil.parseDays(s.getDaysOfWeek()),
                s.getDateFrom(), s.getDateTo(),
                s.isActive(), statusOf(s),
                s.getScreens().stream().map(this::screenRef)
                        .sorted(Comparator.comparing(ScheduleDtos.ScreenRef::name)).toList(),
                s.getCreatedBy() == null ? null : s.getCreatedBy().getFullName(),
                s.getCreatedAt(), s.getUpdatedAt());
    }
}
