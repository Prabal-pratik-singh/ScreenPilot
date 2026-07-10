import { API_BASE } from '../api/client'
import { playerDb } from './db'

/** Queues proof-of-play logs in IndexedDB and syncs them in batches when online. */

export async function queueLog(entry) {
  try {
    await playerDb.addLog(entry)
  } catch {
    // dropping a single log beats crashing the loop
  }
}

export function startLogFlusher(deviceToken, intervalMs = 30000) {
  let stopped = false
  let flushing = false

  const flush = async () => {
    if (stopped || flushing || !navigator.onLine) return
    flushing = true
    try {
      const rows = await playerDb.peekLogs(200)
      if (rows.length > 0) {
        const res = await fetch(`${API_BASE}/api/player/logs`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Device-Token': deviceToken },
          body: JSON.stringify({ logs: rows.map((r) => r.value) }),
        })
        if (res.ok) {
          await playerDb.deleteLogs(rows.map((r) => r.key))
        }
      }
    } catch {
      // offline or server hiccup — logs stay queued
    } finally {
      flushing = false
    }
  }

  const timer = setInterval(flush, intervalMs)
  const onOnline = () => flush()
  window.addEventListener('online', onOnline)
  flush()

  return () => {
    stopped = true
    clearInterval(timer)
    window.removeEventListener('online', onOnline)
  }
}
