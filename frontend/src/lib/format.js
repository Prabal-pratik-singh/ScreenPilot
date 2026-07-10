const IST = 'Asia/Kolkata'

/** "3h 20m", "5d 4h", "45m", "just now" */
export function formatDuration(totalSeconds) {
  if (totalSeconds == null || totalSeconds < 0) return ''
  const s = Math.floor(totalSeconds)
  if (s < 60) return 'just now'
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  if (d > 0) return h > 0 ? `${d}d ${h}h` : `${d}d`
  if (h > 0) return m > 0 ? `${h}h ${m}m` : `${h}h`
  return `${m}m`
}

export function offlineFor(screen) {
  if (!screen?.lastHeartbeatAt) return 'never seen'
  const seconds = (Date.now() - new Date(screen.lastHeartbeatAt).getTime()) / 1000
  return formatDuration(seconds)
}

export function timeAgo(iso) {
  if (!iso) return '—'
  const seconds = (Date.now() - new Date(iso).getTime()) / 1000
  if (seconds < 60) return 'just now'
  return `${formatDuration(seconds)} ago`
}

/** "09 Jul 2026, 14:32 IST" */
export function fmtIST(iso, withTime = true) {
  if (!iso) return '—'
  const date = new Date(iso)
  const opts = withTime
    ? { timeZone: IST, day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }
    : { timeZone: IST, day: '2-digit', month: 'short', year: 'numeric' }
  return new Intl.DateTimeFormat('en-IN', opts).format(date) + (withTime ? ' IST' : '')
}

export function fmtBytes(bytes) {
  if (bytes == null) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

export function fmtSeconds(sec) {
  if (sec == null) return '—'
  const m = Math.floor(sec / 60)
  const s = Math.round(sec % 60)
  if (m === 0) return `${s}s`
  return `${m}m ${s.toString().padStart(2, '0')}s`
}
