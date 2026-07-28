/**
 * Evaluates schedules against IST wall-clock time locally on the player,
 * so content switches on time even without a server push.
 */

// Current wall-clock time in IST (Asia/Kolkata), computed with
// Intl.DateTimeFormat so it is correct no matter the device's own timezone.
// Returns { date: "YYYY-MM-DD", minutes: minutes-since-midnight, dow: "MON" }.
export function istNow() {
  // The 'en-CA' (Canadian English) locale is picked deliberately: its date
  // format is already YYYY-MM-DD, matching how schedules store dates.
  // formatToParts() returns the pieces as a labelled array
  // (e.g. [{type:'year',value:'2026'}, ...]) instead of one string,
  // so each field can be read out without fragile string splitting.
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    weekday: 'short',
    hour12: false,
  }).formatToParts(new Date())
  // Small helper: find the part with the given label and return its value.
  const get = (type) => parts.find((p) => p.type === type)?.value
  // Midnight guard: some engines format midnight as hour "24" instead of
  // "00" in this locale. 24 % 24 = 0, so minutes-since-midnight stays 0-1439
  // and never becomes a bogus 1440.
  const hour = Number(get('hour')) % 24 // en-CA can yield "24" at midnight
  return {
    date: `${get('year')}-${get('month')}-${get('day')}`,
    minutes: hour * 60 + Number(get('minute')),
    // Weekday like "Mon." -> "MON", matching how schedules store days.
    dow: (get('weekday') || '').toUpperCase().slice(0, 3),
  }
}

// "HH:mm" -> minutes since midnight (null when missing).
function toMinutes(hhmm) {
  if (!hhmm) return null
  const [h, m] = hhmm.split(':').map(Number)
  return h * 60 + m
}

// True when a schedule should be playing right now. Checks, in order:
// date range, day-of-week list, then the daily time window.
export function isScheduleLive(s, now = istNow()) {
  // Step 1: date range — both bounds are inclusive, compared as strings
  // (safe because the format is YYYY-MM-DD).
  if (s.dateFrom && now.date < s.dateFrom) return false
  if (s.dateTo && now.date > s.dateTo) return false
  // Step 2: day-of-week filter (empty list = every day).
  if (s.daysOfWeek && s.daysOfWeek.length > 0 && !s.daysOfWeek.includes(now.dow)) return false
  // Step 3: time window; all-day schedules skip it entirely.
  if (s.allDay) return true
  const start = toMinutes(s.startTime)
  const end = toMinutes(s.endTime)
  if (start == null || end == null) return true
  // Normal same-day window: start inclusive, end exclusive.
  if (start < end) return now.minutes >= start && now.minutes < end
  // overnight window (e.g. 20:00–02:00)
  return now.minutes >= start || now.minutes < end
}

/** Timed windows beat all-day; then priority; then most recently updated. */
// From every schedule assigned to the screen, pick the ONE that should play
// now. Sort order acts as the tie-breaker chain described above.
export function pickActiveSchedule(schedules, now = istNow()) {
  const live = (schedules || []).filter((s) => isScheduleLive(s, now))
  if (live.length === 0) return null
  live.sort((a, b) => {
    if (a.allDay !== b.allDay) return a.allDay ? 1 : -1
    if ((b.priority || 0) !== (a.priority || 0)) return (b.priority || 0) - (a.priority || 0)
    return String(b.updatedAt || '').localeCompare(String(a.updatedAt || ''))
  })
  return live[0]
}
