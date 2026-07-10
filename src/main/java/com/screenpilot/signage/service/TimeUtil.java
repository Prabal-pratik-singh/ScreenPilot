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

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private TimeUtil() {
    }

    public static LocalDate todayIST() {
        return LocalDate.now(IST);
    }

    public static LocalTime nowIST() {
        return LocalTime.now(IST);
    }

    public static String dowIST() {
        return DayOfWeek.from(java.time.ZonedDateTime.now(IST)).name().substring(0, 3);
    }

    public static List<String> parseDays(String daysOfWeek) {
        if (daysOfWeek == null || daysOfWeek.isBlank()) {
            return null;
        }
        return Arrays.stream(daysOfWeek.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Whether the schedule is inside its date range and day/time window right now (IST). */
    public static boolean isLiveNow(Schedule s) {
        LocalDate today = todayIST();
        if (s.getDateFrom() != null && today.isBefore(s.getDateFrom())) return false;
        if (s.getDateTo() != null && today.isAfter(s.getDateTo())) return false;
        List<String> days = parseDays(s.getDaysOfWeek());
        if (days != null && !days.contains(dowIST())) return false;
        if (s.isAllDay()) return true;
        if (s.getStartTime() == null || s.getEndTime() == null) return true;
        LocalTime now = nowIST();
        if (s.getStartTime().isBefore(s.getEndTime())) {
            return !now.isBefore(s.getStartTime()) && now.isBefore(s.getEndTime());
        }
        // overnight window, e.g. 20:00 - 02:00
        return !now.isBefore(s.getStartTime()) || now.isBefore(s.getEndTime());
    }
}
