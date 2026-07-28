import { API_BASE } from '../api/client'
import { playerDb } from './db'

/**
 * Downloads required media into IndexedDB (sequential queue, progress
 * reporting) and hands out object URLs for playback. Media that is no
 * longer required is evicted.
 *
 * "generation" is a counter bumped on every sync()/clearAll(); the running
 * download loop checks it and stops if a newer sync has taken over, so an
 * old queue never keeps downloading stale media.
 */
export class DownloadManager {
  constructor(onState) {
    this.onState = onState || (() => {})
    this.state = {} // mediaId -> { status: 'pending'|'downloading'|'downloaded'|'failed', progress: 0-100 }
    this.urls = {} // mediaId -> object URL
    this.queue = []
    this.running = false
    this.generation = 0
  }

  // Push a snapshot of the per-media state to the listener (PlayerPage UI).
  emit() {
    this.onState({ ...this.state })
  }

  // Buckets media ids into cached / downloading / failed — sent to the
  // server inside heartbeats so the portal can show cache health.
  summary() {
    const cached = []
    const downloading = []
    const failed = []
    for (const [id, s] of Object.entries(this.state)) {
      if (s.status === 'downloaded') cached.push(id)
      else if (s.status === 'failed') failed.push(id)
      else downloading.push({ id, progress: s.progress || 0 })
    }
    return { cached, downloading, failed }
  }

  isDownloaded(mediaId) {
    return this.state[mediaId]?.status === 'downloaded'
  }

  // Blob from IndexedDB -> object URL (blob:...) that <video>/<img> can play
  // offline. URLs are memoized so we don't create duplicates for one media.
  async getUrl(mediaId) {
    if (this.urls[mediaId]) return this.urls[mediaId]
    const blob = await playerDb.getMedia(mediaId)
    if (!blob) return null
    const url = URL.createObjectURL(blob)
    this.urls[mediaId] = url
    return url
  }

  /** Reconcile the cache with the required list and start downloading what's missing. */
  async sync(requiredMedia) {
    const gen = ++this.generation
    const required = requiredMedia || []
    const requiredIds = new Set(required.map((m) => String(m.id)))

    // Evict media that is no longer needed
    try {
      const keys = await playerDb.mediaKeys()
      for (const key of keys) {
        if (!requiredIds.has(String(key))) {
          await playerDb.deleteMedia(key)
          if (this.urls[key]) {
            URL.revokeObjectURL(this.urls[key])
            delete this.urls[key]
          }
          delete this.state[key]
        }
      }
    } catch {
      // cache cleanup is best-effort
    }

    // Classify each required media: already cached, mid-download, or queued.
    const toDownload = []
    for (const m of required) {
      const id = String(m.id)
      const existing = await playerDb.getMedia(id)
      if (existing) {
        this.state[id] = { status: 'downloaded', progress: 100 }
      } else if (this.state[id]?.status !== 'downloading') {
        this.state[id] = { status: 'pending', progress: 0 }
        toDownload.push(m)
      }
    }
    this.emit()

    // Replace the queue and kick the worker loop if it isn't already running.
    this.queue = toDownload
    if (!this.running) {
      this.run(gen)
    }
  }

  // Worker loop: downloads queued media one at a time (sequential, so a slow
  // network isn't hammered), stopping early if a newer sync superseded us.
  async run(gen) {
    this.running = true
    while (this.queue.length > 0 && gen === this.generation) {
      const m = this.queue.shift()
      await this.download(m)
    }
    this.running = false
  }

  // Fetch one media file, streaming the body chunk by chunk so we can report
  // percentage progress, then store the finished Blob in IndexedDB.
  async download(m) {
    const id = String(m.id)
    this.state[id] = { status: 'downloading', progress: 0 }
    this.emit()
    try {
      const res = await fetch(`${API_BASE}${m.url || `/api/media/${id}/file`}`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      // Progress is based on Content-Length when the server sends it,
      // falling back to the size the config reported.
      const total = Number(res.headers.get('Content-Length')) || m.sizeBytes || 0
      const reader = res.body.getReader()
      const chunks = []
      let received = 0
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        chunks.push(value)
        received += value.length
        const progress = total ? Math.min(99, Math.round((received / total) * 100)) : 50
        if (progress !== this.state[id].progress) {
          this.state[id] = { status: 'downloading', progress }
          this.emit()
        }
      }
      const blob = new Blob(chunks, { type: res.headers.get('Content-Type') || m.mimeType || 'application/octet-stream' })
      await playerDb.putMedia(id, blob)
      this.state[id] = { status: 'downloaded', progress: 100 }
    } catch (err) {
      this.state[id] = { status: 'failed', progress: 0, error: String(err?.message || err) }
    }
    this.emit()
  }

  // Wipe the whole cache (used by the "clear cache" remote command):
  // abort the queue, delete every blob and revoke every object URL.
  async clearAll() {
    this.generation++
    this.queue = []
    await playerDb.clearMedia()
    Object.values(this.urls).forEach((u) => URL.revokeObjectURL(u))
    this.urls = {}
    this.state = {}
    this.emit()
  }
}
