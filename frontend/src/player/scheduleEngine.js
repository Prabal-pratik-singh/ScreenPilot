/**
 * Evaluates schedules against IST wall-clock time locally on the player,
 * so content switches on time even without a server push.
 */

export function istNow() {
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
  const get = (type) => parts.find((p) => p.type === type)?.value
  const hour = Number(get('hour')) % 24 // en-CA can yield "24" at midnight
  return {
    date: `${get('year')}-${get('month')}-${get('day')}`,
    minutes: hour * 60 + Number(get('minute')),
    dow: (get('weekday') || '').toUpperCase().slice(0, 3),
  }
}

function toMinutes(hhmm) {
  if (!hhmm) return null
  const [h, m] = hhmm.split(':').map(Number)
  return h * 60 + m
}

export function isScheduleLive(s, now = istNow()) {
  if (s.dateFrom && now.date < s.dateFrom) return false
  if (s.dateTo && now.date > s.dateTo) return false
  if (s.daysOfWeek && s.daysOfWeek.length > 0 && !s.daysOfWeek.includes(now.dow)) return false
  if (s.allDay) return true
  const start = toMinutes(s.startTime)
  const end = toMinutes(s.endTime)
  if (start == null || end == null) return true
  if (start < end) return now.minutes >= start && now.minutes < end
  // overnight window (e.g. 20:00–02:00)
  return now.minutes >= start || now.minutes < end
}

/** Timed windows beat all-day; then priority; then most recently updated. */
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
