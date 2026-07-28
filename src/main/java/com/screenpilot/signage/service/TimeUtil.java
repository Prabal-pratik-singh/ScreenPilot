package com.screenpilot.signage.service;

import com.screenpilot.signage.domain.Schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** All scheduling decisions are made in IST regardless of server timezone. */
public final class TimeUtil {

    // The one timezone all schedule logic uses. The database stores absolute
    // UTC instants, but rules like "play 09:00-18:00" mean IST wall-clock time
    // for the stores — pinning the zone here means a server running in UTC (or
    // anywhere else) still makes exactly the same play/don't-play decisions.
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // utility class: the private constructor stops anyone from instantiating it
    private TimeUtil() {
    }

    /** Today's date in IST. */
    public static LocalDate todayIST() {
        return LocalDate.now(IST);
    }

    /** Current wall-clock time in IST. */
    public static LocalTime nowIST() {
        return LocalTime.now(IST);
    }

    /** Current day-of-week in IST as a 3-letter code, e.g. "MON". */
    public static String dowIST() {
        // DayOfWeek.name() gives "MONDAY"; the first three letters ("MON")
        // match the codes stored in Schedule.daysOfWeek
        return DayOfWeek.from(java.time.ZonedDateTime.now(IST)).name().substring(0, 3);
    }

    /** Splits a stored "MON,TUE,..." string into codes; null means "every day". */
    public static List<String> parseDays(String daysOfWeek) {
        // nothing stored = no day restriction; callers treat null as "every day"
        if (daysOfWeek == null || daysOfWeek.isBlank()) {
            return null;
        }
        // split the CSV and clean each piece (trim spaces, uppercase, drop
        // empties) so a slightly messy stored value like "mon, tue," still parses
        return Arrays.stream(daysOfWeek.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Whether the schedule is inside its date range and day/time window right now (IST). */
    public static boolean isLiveNow(Schedule s) {
        // checks run from broadest to narrowest — date range, then day of week,
        // then the all-day shortcut, then the time window; first miss = not live
        LocalDate today = todayIST();
        // 1. date range: before the start date or past the end date means not live
        if (s.getDateFrom() != null && today.isBefore(s.getDateFrom())) return false;
        if (s.getDateTo() != null && today.isAfter(s.getDateTo())) return false;
        // 2. day of week: a stored subset must include today's code (null = every day)
        List<String> days = parseDays(s.getDaysOfWeek());
        if (days != null && !days.contains(dowIST())) return false;
        // 3. all-day schedules are live for the whole matching day — no time check needed
        if (s.isAllDay()) return true;
        // defensive: a timed schedule missing its times behaves like all-day
        if (s.getStartTime() == null || s.getEndTime() == null) return true;
        LocalTime now = nowIST();
        // 4a. normal same-day window: live when start <= now < end
        // (start inclusive, end exclusive, so back-to-back windows don't overlap)
        if (s.getStartTime().isBefore(s.getEndTime())) {
            return !now.isBefore(s.getStartTime()) && now.isBefore(s.getEndTime());
        }
        // overnight window, e.g. 20:00 - 02:00
        // 4b. start > end means the window wraps past midnight, so "now" only has
        // to fall in ONE half: after start (the evening part) OR before end (the
        // early-morning part) — note the OR where the normal case uses AND
        return !now.isBefore(s.getStartTime()) || now.isBefore(s.getEndTime());
    }
}
