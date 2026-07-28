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

/**
 * Manages schedules — the rules deciding WHICH content (playlist or layout)
 * plays on WHICH screens and WHEN (time window, days of week, date range, all
 * in IST). Also detects overlapping schedules before save and pushes changes
 * to affected screens through the ContentPushService (WebSocket).
 */
@Service
public class ScheduleService {

    // Collaborators Spring wires in: repositories for database access,
    // ScreenService for group-scoped screen lookups, and ContentPushService
    // for telling players over WebSocket that their content changed.
    private final ScheduleRepository scheduleRepository;
    private final PlaylistRepository playlistRepository;
    private final com.screenpilot.signage.repo.LayoutRepository layoutRepository;
    private final UserRepository userRepository;
    private final ScreenService screenService;
    private final ContentPushService contentPush;

    /** Constructor injection: Spring supplies every dependency when it builds this service. */
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

    /** All schedules the user may see: at least one target screen must be in their groups. */
    @Transactional(readOnly = true)
    public List<ScheduleDtos.ScheduleResponse> list() {
        // who is asking? CurrentUser reads the logged-in principal from the security context
        AppPrincipal user = CurrentUser.get();
        // fetch newest-first, then apply group scoping:
        // - unrestricted users (admins) see every schedule
        // - everyone else only sees schedules where at least one target screen
        //   sits in a group they belong to
        return scheduleRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(s -> user.unrestricted() || s.getScreens().stream().anyMatch(
                        scr -> scr.getGroup() != null && user.groupIds().contains(scr.getGroup().getId())))
                .map(this::toDto)
                .toList();
    }

    /** One schedule by id, or 404. */
    @Transactional(readOnly = true)
    public ScheduleDtos.ScheduleResponse get(UUID id) {
        return toDto(getEntity(id));
    }

    // loads the entity together with its screens in one query, or fails with a 404
    private Schedule getEntity(UUID id) {
        return scheduleRepository.findWithScreensById(id)
                .orElseThrow(() -> ApiException.notFound("Schedule not found"));
    }

    // ---------------------------------------------------------- mutations

    /** Creates a schedule, optionally pausing overridden ones, then pushes to affected screens. */
    @Transactional
    public ScheduleDtos.ScheduleResponse create(ScheduleDtos.SaveScheduleRequest req) {
        // 1. validate and populate the new schedule from the request
        Schedule schedule = new Schedule(req.name().trim(), req.contentType());
        apply(schedule, req);
        // stamp who created it (best effort — stays null if the user row is gone)
        schedule.setCreatedBy(userRepository.findById(CurrentUser.get().id()).orElse(null));
        // 2. pause any schedules the user chose to override; collect every screen touched
        Set<UUID> affected = applyOverridesAndCollect(req, schedule);
        scheduleRepository.save(schedule);
        // 3. push fresh config to all affected screens
        contentPush.schedulesUpdated(affected);
        return toDto(schedule);
    }

    /** Updates a schedule; screens removed from it are also refreshed so they stop playing it. */
    @Transactional
    public ScheduleDtos.ScheduleResponse update(UUID id, ScheduleDtos.SaveScheduleRequest req) {
        Schedule schedule = getEntity(id);
        // remember the previous screen set — those screens need a push even if unassigned now
        Set<UUID> before = schedule.getScreens().stream().map(Screen::getId).collect(Collectors.toSet());
        schedule.setName(req.name().trim());
        schedule.setContentType(req.contentType());
        apply(schedule, req);
        schedule.setUpdatedAt(Instant.now());
        Set<UUID> affected = applyOverridesAndCollect(req, schedule);
        // union of old + new screen sets: a screen dropped from the schedule
        // must also be refreshed so it stops playing content it no longer has
        affected.addAll(before);
        scheduleRepository.save(schedule);
        contentPush.schedulesUpdated(affected);
        return toDto(schedule);
    }

    /** Pauses or resumes a schedule and refreshes its screens. */
    @Transactional
    public void setActive(UUID id, boolean active) {
        Schedule schedule = getEntity(id);
        // flip the flag and bump updatedAt so clients can see something changed
        schedule.setActive(active);
        schedule.setUpdatedAt(Instant.now());
        scheduleRepository.save(schedule);
        // tell every screen this schedule targets to refetch its config now
        contentPush.schedulesUpdated(schedule.getScreens().stream().map(Screen::getId).collect(Collectors.toSet()));
    }

    /** Deletes a schedule and refreshes the screens that were playing it. */
    @Transactional
    public void delete(UUID id) {
        Schedule schedule = getEntity(id);
        // capture the screen ids BEFORE deleting — the link rows vanish with the schedule
        Set<UUID> screens = schedule.getScreens().stream().map(Screen::getId).collect(Collectors.toSet());
        scheduleRepository.delete(schedule);
        // flush sends the DELETE to the database immediately, so nothing that
        // runs later in this transaction can still see the dead schedule
        scheduleRepository.flush();
        contentPush.schedulesUpdated(screens);
    }

    // ---------------------------------------------------------- conflicts

    /** Dry-run before save: lists existing schedules that would clash with the submitted one. */
    @Transactional(readOnly = true)
    public ScheduleDtos.ConflictResponse previewConflicts(ScheduleDtos.SaveScheduleRequest req, UUID excludeId) {
        // 1. build an unsaved candidate from the request so it can be compared like a real schedule
        Schedule candidate = new Schedule(req.name() == null ? "New schedule" : req.name(), req.contentType());
        apply(candidate, req);

        // 2. check every active schedule already targeting any of the same screens
        List<UUID> screenIds = req.screenIds();
        // keyed by schedule id so each clashing schedule is reported only once;
        // LinkedHashMap keeps a stable first-seen order for the warning list
        Map<UUID, ScheduleDtos.ConflictInfo> conflicts = new LinkedHashMap<>();
        // the query narrows to schedules that are active AND share a screen with the request
        for (Schedule existing : scheduleRepository.findActiveForScreens(screenIds)) {
            // when editing, the schedule being edited is not a conflict with itself
            if (excludeId != null && existing.getId().equals(excludeId)) {
                continue;
            }
            // 3. a conflict = not expired, same specificity, and the time windows really overlap
            if (!isExpired(existing) && sameSpecificityOverlap(candidate, existing)) {
                // 4. report only the screens both schedules share
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
        // mixed specificity (one timed, one all-day) is never a conflict:
        // the timed one carries priority 10 vs the all-day one's 0, so the
        // player deterministically plays the timed one during its window and
        // falls back to the all-day loop the rest of the time
        if (a.isAllDay() != b.isAllDay()) {
            return false;
        }
        // same specificity: it is a real clash only when ALL three dimensions
        // overlap — calendar dates, days of the week, and time of day
        return dateRangesIntersect(a, b) && daysIntersect(a, b) && windowsIntersect(a, b);
    }

    // date ranges overlap; a missing bound is treated as open-ended (MIN/MAX)
    private boolean dateRangesIntersect(Schedule a, Schedule b) {
        // substitute the extremes for missing bounds so "no start date" behaves
        // like "since forever" and "no end date" like "until forever"
        LocalDate aFrom = a.getDateFrom() == null ? LocalDate.MIN : a.getDateFrom();
        LocalDate aTo = a.getDateTo() == null ? LocalDate.MAX : a.getDateTo();
        LocalDate bFrom = b.getDateFrom() == null ? LocalDate.MIN : b.getDateFrom();
        LocalDate bTo = b.getDateTo() == null ? LocalDate.MAX : b.getDateTo();
        // classic interval test: the ranges overlap unless one starts strictly
        // after the other ends (both bounds are inclusive dates)
        return !aFrom.isAfter(bTo) && !bFrom.isAfter(aTo);
    }

    // day-of-week sets share at least one day; null means "every day", which always overlaps
    private boolean daysIntersect(Schedule a, Schedule b) {
        // parseDays turns the stored CSV ("MON,WED") back into a list; null = every day
        List<String> daysA = TimeUtil.parseDays(a.getDaysOfWeek());
        List<String> daysB = TimeUtil.parseDays(b.getDaysOfWeek());
        // a schedule that runs every day must share a day with anything
        if (daysA == null || daysB == null) {
            return true;
        }
        // otherwise they overlap as soon as any single day code appears in both lists
        return daysA.stream().anyMatch(daysB::contains);
    }

    // time-of-day windows overlap (all-day counts as always overlapping)
    private boolean windowsIntersect(Schedule a, Schedule b) {
        // an all-day schedule covers 00:00-24:00, so it overlaps any window
        // (by this point both sides have the same allDay flag anyway)
        if (a.isAllDay() || b.isAllDay()) {
            return true;
        }
        // defensive: a timed schedule missing its start time counts as always on
        if (a.getStartTime() == null || b.getStartTime() == null) {
            return true;
        }
        // treat each window as one or two same-day intervals (overnight wraps)
        for (int[] ia : intervals(a)) {
            for (int[] ib : intervals(b)) {
                // classic overlap test for half-open ranges: they intersect
                // exactly when each one starts before the other one ends
                if (ia[0] < ib[1] && ib[0] < ia[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    // a window as [start,end) second-of-day intervals; overnight windows split into two
    private List<int[]> intervals(Schedule s) {
        // work in seconds-of-day (0..86399) so plain integer comparisons replace time objects
        int start = s.getStartTime().toSecondOfDay();
        int end = s.getEndTime().toSecondOfDay();
        // normal window (e.g. 09:00-18:00): a single interval
        if (start < end) {
            return List.of(new int[]{start, end});
        }
        // overnight window (e.g. 20:00-02:00): split at midnight into
        // [20:00, 24:00) plus [00:00, 02:00) — two same-day pieces the
        // simple overlap test above can handle (86400 = seconds in a day)
        return List.of(new int[]{start, 86400}, new int[]{0, end});
    }

    // ---------------------------------------------------------- helpers

    // deactivates the schedules the user chose to override and returns every screen id affected
    private Set<UUID> applyOverridesAndCollect(ScheduleDtos.SaveScheduleRequest req, Schedule schedule) {
        // start with the screens the new/edited schedule itself targets
        Set<UUID> affected = schedule.getScreens().stream().map(Screen::getId).collect(Collectors.toSet());
        // overrideScheduleIds = conflicts the user saw in the preview and chose to win against
        if (req.overrideScheduleIds() != null) {
            for (UUID overrideId : req.overrideScheduleIds()) {
                scheduleRepository.findWithScreensById(overrideId).ifPresent(existing -> {
                    // losers are paused (active=false), never deleted — the user can resume them later
                    existing.setActive(false);
                    existing.setUpdatedAt(Instant.now());
                    scheduleRepository.save(existing);
                    // the loser's screens also need a push — their lineup just changed
                    existing.getScreens().forEach(s -> affected.add(s.getId()));
                });
            }
        }
        return affected;
    }

    // validates the request and copies it onto the entity (content, window, days, dates, screens)
    private void apply(Schedule schedule, ScheduleDtos.SaveScheduleRequest req) {
        // 1. resolve the content reference — a playlist or a layout, never both
        if (req.contentType() == Schedule.ContentType.PLAYLIST) {
            if (req.playlistId() == null) {
                throw ApiException.badRequest("playlistId is required for playlist schedules");
            }
            schedule.setPlaylist(playlistRepository.findById(req.playlistId())
                    .orElseThrow(() -> ApiException.badRequest("Playlist not found")));
            // clear the other content pointer so a schedule never references both
            schedule.setLayoutId(null);
        } else {
            if (req.layoutId() == null) {
                throw ApiException.badRequest("layoutId is required for layout schedules");
            }
            if (!layoutRepository.existsById(req.layoutId())) {
                throw ApiException.badRequest("Layout not found");
            }
            schedule.setLayoutId(req.layoutId());
            // same mirror-clearing for the layout branch
            schedule.setPlaylist(null);
        }

        // 2. time window: all-day schedules have priority 0, timed ones 10 (timed wins)
        schedule.setAllDay(req.allDay());
        if (req.allDay()) {
            // all-day = the base/fallback loop: no times stored, lowest priority (0)
            schedule.setStartTime(null);
            schedule.setEndTime(null);
            schedule.setPriority(0);
        } else {
            // timed schedules must carry valid HH:mm start and end values
            schedule.setStartTime(parseTime(req.startTime(), "startTime"));
            schedule.setEndTime(parseTime(req.endTime(), "endTime"));
            // equal start and end would be a zero-length window — meaningless,
            // and ambiguous (0 hours? 24 hours?), so it is rejected outright
            if (schedule.getStartTime().equals(schedule.getEndTime())) {
                throw ApiException.badRequest("Start and end time cannot be the same");
            }
            // the player always picks the highest-priority live schedule, so a
            // timed window (10) automatically overrides the all-day loop (0)
            // during its hours — that is the whole priority system
            schedule.setPriority(10); // timed windows beat all-day schedules
        }
        // 3. days of week: store a comma list only when it's a real subset; all 7 days = null
        if (req.daysOfWeek() != null && !req.daysOfWeek().isEmpty() && req.daysOfWeek().size() < 7) {
            List<String> valid = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
            // normalize the input: trim spaces, uppercase, drop duplicates
            List<String> days = req.daysOfWeek().stream().map(d -> d.trim().toUpperCase()).distinct().toList();
            // reject anything that is not one of the seven 3-letter codes
            if (!valid.containsAll(days)) {
                throw ApiException.badRequest("daysOfWeek must be MON..SUN");
            }
            // persisted as a plain CSV string in one column, e.g. "MON,WED,FRI"
            schedule.setDaysOfWeek(String.join(",", days));
        } else {
            // null means "runs every day" — picking all 7 days is stored the same
            // way, so the common case stays one cheap null check everywhere
            schedule.setDaysOfWeek(null);
        }
        // 4. date range sanity check
        if (req.dateFrom() != null && req.dateTo() != null && req.dateTo().isBefore(req.dateFrom())) {
            throw ApiException.badRequest("End date is before start date");
        }
        schedule.setDateFrom(req.dateFrom());
        schedule.setDateTo(req.dateTo());

        // 5. Screens: verify access and existence
        Set<Screen> screens = new HashSet<>();
        // wrapping the ids in a HashSet drops duplicates from the request
        for (UUID screenId : new HashSet<>(req.screenIds())) {
            // getAccessible is the group-scoping gate: 404 if the screen does not
            // exist, 403 if it is not in one of the caller's groups — so users
            // can never schedule content onto screens they do not manage
            screens.add(screenService.getAccessible(screenId));
        }
        schedule.setScreens(screens);
    }

    // strict "HH:mm" parsing with a field-specific error message
    private LocalTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " is required for timed schedules");
        }
        try {
            // LocalTime.parse expects ISO time, so "09:30" parses and junk throws
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be HH:mm");
        }
    }

    // a schedule whose end date already passed (in IST) can never play again
    private boolean isExpired(Schedule s) {
        return s.getDateTo() != null && s.getDateTo().isBefore(TimeUtil.todayIST());
    }

    // display status: EXPIRED > PAUSED > UPCOMING > ACTIVE
    private String statusOf(Schedule s) {
        // derived fresh from the data every time (never stored in the DB), so it
        // can never go stale: end date passed -> EXPIRED; manually switched off
        // -> PAUSED; start date still ahead -> UPCOMING; otherwise ACTIVE
        if (isExpired(s)) return "EXPIRED";
        if (!s.isActive()) return "PAUSED";
        if (s.getDateFrom() != null && s.getDateFrom().isAfter(TimeUtil.todayIST())) return "UPCOMING";
        return "ACTIVE";
    }

    // human-readable summary of when a schedule runs, shown in conflict warnings
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

    // small projection of a screen (id + display fields) embedded in responses
    private ScheduleDtos.ScreenRef screenRef(Screen s) {
        return new ScheduleDtos.ScreenRef(s.getId(), s.getName(), s.getStoreName(), s.getCity(), s.getState());
    }

    // converts the JPA entity into the API response record the frontend consumes
    private ScheduleDtos.ScheduleResponse toDto(Schedule s) {
        // the schedule stores only the layout's id (no JPA relation), so the
        // display name is looked up separately when one is set
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
