import { API_BASE } from '../api/client'
import { playerDb } from './db'

/** Queues proof-of-play logs in IndexedDB and syncs them in batches when online. */
// "Proof-of-play" = a record that item X was actually on screen from time A
// to time B. Writing to IndexedDB first (instead of POSTing directly) means
// no log is lost when the TV is offline — they upload later in batches.

// Store one playback log locally; the flusher uploads it later.
export async function queueLog(entry) {
  try {
    await playerDb.addLog(entry)
  } catch {
    // dropping a single log beats crashing the loop
    // (an unhandled rejection here would bubble into the playback code that
    // called us — losing one analytics row is the cheaper failure)
  }
}

// Starts a background loop that every `intervalMs` (and whenever the browser
// comes back online) uploads up to 200 queued logs in one POST, deleting them
// from IndexedDB only after the server accepts the batch. Returns a stop().
export function startLogFlusher(deviceToken, intervalMs = 30000) {
  // Two flags (plain variables, captured by the closure below):
  // `stopped` flips true on teardown so a flush already in flight becomes a
  // no-op next time; `flushing` is the re-entrancy lock.
  let stopped = false
  let flushing = false

  const flush = async () => {
    // `flushing` guards against overlapping runs if a flush is slow.
    // Three reasons to skip: torn down, already mid-flush, or offline
    // (no point attempting a POST that will certainly fail).
    if (stopped || flushing || !navigator.onLine) return
    flushing = true
    try {
      // Take at most 200 of the OLDEST logs — a bounded batch keeps the
      // request body small even after days offline (backlog drains 200/run).
      const rows = await playerDb.peekLogs(200)
      if (rows.length > 0) {
        const res = await fetch(`${API_BASE}/api/player/logs`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Device-Token': deviceToken },
          // Send only the log values; the IndexedDB keys stay local.
          body: JSON.stringify({ logs: rows.map((r) => r.value) }),
        })
        // Delete ONLY when the server said OK. If the response is lost the
        // batch is re-sent next run — "at-least-once" delivery: a log may
        // arrive twice (server can dedupe), but it is never silently lost.
        if (res.ok) {
          await playerDb.deleteLogs(rows.map((r) => r.key))
        }
      }
    } catch {
      // offline or server hiccup — logs stay queued
    } finally {
      // Always release the lock, success or failure.
      flushing = false
    }
  }

  // Flush every 30s by default...
  const timer = setInterval(flush, intervalMs)
  // ...and also the moment the browser regains network, so a long offline
  // backlog starts draining immediately instead of waiting a full interval.
  const onOnline = () => flush()
  window.addEventListener('online', onOnline)
  // Plus one flush right away, to catch logs left over from before a reload.
  flush()

  // Teardown handle: the caller (a React effect) runs this on unmount.
  // It stops the timer, detaches the listener, and neutralizes in-flight work.
  return () => {
    stopped = true
    clearInterval(timer)
    window.removeEventListener('online', onOnline)
  }
}
