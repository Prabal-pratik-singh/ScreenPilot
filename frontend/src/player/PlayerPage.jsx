// The TV player, served at /player. This one component runs the whole device
// lifecycle: pairing (6-digit code shown until an admin claims it), fetching
// the playback config, caching media through the DownloadManager, evaluating
// schedules every few seconds, heartbeating over STOMP over WebSocket (HTTP
// fallback), reacting to remote commands, and rendering the active content.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Client } from '@stomp/stompjs'
import { WS_URL, API_BASE } from '../api/client'
import { playerApi, loadDevice, saveDevice, storageEstimateMb } from './playerApi'
import { DownloadManager } from './downloadManager'
import { pickActiveSchedule } from './scheduleEngine'
import { queueLog, startLogFlusher } from './logQueue'
import PlaylistPlayer from './PlaylistPlayer'
import LayoutRenderer from './LayoutRenderer'
import { Logo } from '../components/Logo'

const APP_VERSION = '1.2.0'
const HEARTBEAT_MS = 30000
const CONFIG_POLL_MS = 5 * 60 * 1000
const SCHEDULE_TICK_MS = 5000
const CONFIG_CACHE_KEY = 'screenpilot.player.config'

// Big IST clock shown on the idle screen; re-renders every second.
function ISTClock({ className }) {
  const [now, setNow] = useState(new Date())
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(t)
  }, [])
  const time = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(now)
  const date = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata', weekday: 'long', day: '2-digit', month: 'long', year: 'numeric',
  }).format(now)
  return (
    <div className={className}>
      <p className="text-6xl font-bold tabular-nums tracking-tight">{time}</p>
      <p className="text-lg text-white/60 mt-2">{date} · IST</p>
    </div>
  )
}

// Full-screen pairing view: shows the 6-digit code the admin must type into
// the portal, plus expiry / connection-error states.
function PairingScreen({ code, expired, error }) {
  return (
    <div className="h-full w-full bg-gradient-to-br from-[#100A1E] via-app to-black text-white flex flex-col items-center justify-center gap-10 p-8 relative overflow-hidden">
      {/* drifting glow blobs matching the portal login backdrop */}
      <div className="absolute -top-24 -right-24 h-96 w-96 rounded-full bg-primary-600/20 blur-3xl animate-float" />
      <div className="absolute -bottom-32 -left-24 h-96 w-96 rounded-full bg-accent/15 blur-3xl animate-float [animation-delay:3s]" />
      <Logo dark size="lg" />
      <div className="text-center relative">
        <p className="text-white/60 text-xl mb-6">Enter this code in the portal to pair this screen</p>
        <div className="flex gap-3 justify-center">
          {(code || '······').split('').map((ch, i) => (
            <span
              key={`${code}-${i}`}
              className="w-20 h-24 rounded-2xl bg-gradient-to-b from-white/15 to-white/5 border border-primary-500/40 shadow-[0_8px_30px_rgba(139,92,246,0.25)] flex items-center justify-center text-6xl font-bold text-primary-400 animate-pop-in"
              style={{ animationDelay: `${i * 70}ms` }}
            >
              {ch}
            </span>
          ))}
        </div>
        {expired && <p className="text-warning mt-6 text-lg">Code expired — getting a new one…</p>}
        {error && <p className="text-danger mt-6 text-lg">{error}</p>}
        {!expired && !error && (
          <p className="text-white/40 mt-6">Portal → Screens → <span className="text-white/70 font-semibold">Add screen</span></p>
        )}
      </div>
      <p className="absolute bottom-6 text-white/30 text-sm">ScreenPilot Player v{APP_VERSION}</p>
    </div>
  )
}

// Shown when the screen is paired but has nothing scheduled to play.
function IdleScreen({ screenName, note }) {
  return (
    <div className="h-full w-full bg-gradient-to-br from-[#100A1E] via-app to-black text-white flex flex-col items-center justify-center gap-12">
      <Logo dark size="lg" />
      <ISTClock className="text-center" />
      <div className="text-center">
        <p className="text-2xl text-white/70 font-medium">{screenName}</p>
        <p className="text-white/40 mt-2">{note || 'Waiting for scheduled content…'}</p>
      </div>
    </div>
  )
}

// Shown while required media is still downloading: overall progress bar plus
// the percentage of the file currently in flight.
function PreparingScreen({ screenName, downloadState }) {
  const entries = Object.entries(downloadState)
  const done = entries.filter(([, s]) => s.status === 'downloaded').length
  const total = entries.length || 1
  const pct = Math.round((done / total) * 100)
  const current = entries.find(([, s]) => s.status === 'downloading')
  return (
    <div className="h-full w-full bg-gradient-to-br from-[#100A1E] via-app to-black text-white flex flex-col items-center justify-center gap-8">
      <Logo dark size="lg" />
      <div className="w-[420px] max-w-[80vw] text-center">
        <p className="text-white/70 text-lg mb-4">Downloading content… {done}/{entries.length}</p>
        <div className="h-2 rounded-full bg-white/10 overflow-hidden">
          <div className="h-full bg-grad-primary transition-all" style={{ width: `${pct}%` }} />
        </div>
        {current && <p className="text-white/40 text-sm mt-3">{current[1].progress}% of current file</p>}
      </div>
      <p className="text-white/30">{screenName}</p>
    </div>
  )
}

/** Kiosk cursor: visible while the mouse moves, hidden after 3s of inactivity. */
function useAutoHideCursor(timeoutMs = 3000) {
  const [hidden, setHidden] = useState(true)
  useEffect(() => {
    let timer = null
    const onMove = () => {
      setHidden(false)
      clearTimeout(timer)
      timer = setTimeout(() => setHidden(true), timeoutMs)
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('pointerdown', onMove)
    return () => {
      clearTimeout(timer)
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('pointerdown', onMove)
    }
  }, [timeoutMs])
  return hidden
}

// The main player component. State flows: unpaired -> pairing screen;
// paired -> fetch config -> download media -> pick schedule -> play.
export default function PlayerPage() {
  const [device, setDevice] = useState(loadDevice)
  const [pairCode, setPairCode] = useState(null)
  const [pairState, setPairState] = useState({ expired: false, error: null })
  const cursorHidden = useAutoHideCursor()
  const [config, setConfig] = useState(null)
  const [downloadState, setDownloadState] = useState({})
  const [activeSchedule, setActiveSchedule] = useState(null)
  const stompRef = useRef(null)
  const wsConnectedRef = useRef(false)
  const deviceRef = useRef(device)
  deviceRef.current = device
  const nowPlayingRef = useRef(null)
  const configRef = useRef(null)
  configRef.current = config

  const dm = useMemo(() => new DownloadManager(setDownloadState), [])
  const dmRef = useRef(dm)

  // ---------------- pairing ----------------
  // Runs only while unpaired: request a code, then poll the server every few
  // seconds until an admin claims it (-> we get a device token), the code
  // expires (-> request a fresh one), or the server is unreachable (-> retry).
  useEffect(() => {
    if (device) return undefined
    let cancelled = false
    let pollTimer = null
    const start = async () => {
      try {
        const res = await playerApi.requestPairCode(navigator.userAgent)
        if (cancelled) return
        setPairCode(res.code)
        setPairState({ expired: false, error: null })
        const expiresAt = new Date(res.expiresAt).getTime()
        const poll = async () => {
          if (cancelled) return
          if (Date.now() > expiresAt) {
            setPairState({ expired: true, error: null })
            start()
            return
          }
          try {
            const p = await playerApi.pollPairing(res.code)
            if (cancelled) return
            if (p.status === 'PAIRED' && p.deviceToken) {
              const dev = { deviceToken: p.deviceToken, screenId: p.screenId, screenName: p.screenName }
              saveDevice(dev)
              setDevice(dev)
              return
            }
            if (p.status === 'EXPIRED') {
              setPairState({ expired: true, error: null })
              start()
              return
            }
          } catch {
            /* network hiccup — keep polling */
          }
          pollTimer = setTimeout(poll, res.pollIntervalMs || 3000)
        }
        pollTimer = setTimeout(poll, res.pollIntervalMs || 3000)
      } catch (err) {
        if (!cancelled) {
          const detail = err.status
            ? `server error HTTP ${err.status}` // reached nginx, backend answered badly
            : 'network blocked or server offline' // request never arrived
          setPairState({ expired: false, error: `Cannot reach the server (${detail}). Retrying…` })
          pollTimer = setTimeout(start, 5000)
        }
      }
    }
    start()
    return () => {
      cancelled = true
      clearTimeout(pollTimer)
    }
  }, [device])

  // Forget the pairing (used when the server answers 401 = token revoked);
  // the page falls back to the pairing screen automatically.
  const unpair = useCallback(() => {
    saveDevice(null)
    localStorage.removeItem(CONFIG_CACHE_KEY)
    setConfig(null)
    setDevice(null)
  }, [])

  // ---------------- config (with offline fallback) ----------------
  // Pull the playback config (schedules, playlists, layouts, requiredMedia),
  // cache it in localStorage for offline boots, and kick the download
  // manager to sync media. Called on load, every 5 min, on WS pushes, and
  // whenever the browser regains network.
  const refreshConfig = useCallback(async () => {
    const dev = deviceRef.current
    if (!dev) return
    try {
      const cfg = await playerApi.config(dev.deviceToken)
      setConfig(cfg)
      localStorage.setItem(CONFIG_CACHE_KEY, JSON.stringify(cfg))
      dmRef.current.sync(cfg.requiredMedia || [])
    } catch (err) {
      if (err.status === 401) {
        unpair()
        return
      }
      // offline: fall back to the cached config so playback continues
      if (!configRef.current) {
        try {
          const cached = JSON.parse(localStorage.getItem(CONFIG_CACHE_KEY) || 'null')
          if (cached) {
            setConfig(cached)
            dmRef.current.sync(cached.requiredMedia || [])
          }
        } catch {
          /* no cached config */
        }
      }
    }
  }, [unpair])

  useEffect(() => {
    if (!device) return undefined
    refreshConfig()
    const poll = setInterval(refreshConfig, CONFIG_POLL_MS)
    const onOnline = () => refreshConfig()
    window.addEventListener('online', onOnline)
    return () => {
      clearInterval(poll)
      window.removeEventListener('online', onOnline)
    }
  }, [device, refreshConfig])

  // ---------------- schedule engine tick ----------------
  // Every 5s re-evaluate which schedule should be live (IST wall-clock);
  // only swap state when the winning schedule actually changed, so playback
  // isn't restarted needlessly.
  useEffect(() => {
    if (!config) return undefined
    const evaluate = () => {
      const next = pickActiveSchedule(config.schedules || [])
      setActiveSchedule((prev) => (prev?.id === next?.id ? prev : next))
    }
    evaluate()
    const t = setInterval(evaluate, SCHEDULE_TICK_MS)
    return () => clearInterval(t)
  }, [config])

  // ---------------- heartbeats ----------------
  // Assemble the status snapshot the portal shows for this screen:
  // playing/idle, current item, app version, storage usage, cache summary.
  const buildHeartbeat = useCallback(async () => {
    const storage = await storageEstimateMb()
    const item = nowPlayingRef.current
    return {
      status: item ? 'PLAYING' : 'IDLE',
      currentItemName: item ? item.title || item.media?.name || item.url || 'item' : 'Idle',
      currentItemMediaId: item?.media?.id || null,
      appVersion: APP_VERSION,
      storageUsedMb: storage.usedMb,
      storageTotalMb: storage.totalMb,
      mediaState: dmRef.current.summary(),
    }
  }, [])

  // Send a heartbeat, preferring the open STOMP connection (cheap, no HTTP
  // round-trip) and falling back to the REST endpoint when the socket is down.
  const sendHeartbeat = useCallback(async () => {
    const dev = deviceRef.current
    if (!dev) return
    const payload = await buildHeartbeat()
    const client = stompRef.current
    if (client && wsConnectedRef.current) {
      try {
        client.publish({
          destination: '/app/player/heartbeat',
          headers: { 'x-device-token': dev.deviceToken },
          body: JSON.stringify(payload),
        })
        return
      } catch {
        /* fall back to HTTP */
      }
    }
    try {
      await playerApi.heartbeat(dev.deviceToken, payload)
    } catch (err) {
      if (err.status === 401) unpair()
    }
  }, [buildHeartbeat, unpair])

  // ---------------- remote commands ----------------
  // Commands pushed from the portal: RELOAD (refresh the page), CLEAR_CACHE
  // (wipe IndexedDB + refetch), SCREENSHOT. Each is acknowledged over HTTP
  // so the portal can show delivery status.
  const handleCommand = useCallback(
    async (msg) => {
      const command = msg.command || msg.type
      const dev = deviceRef.current
      const ack = () => {
        if (msg.commandId && dev) {
          fetch(`${API_BASE}/api/player/commands/${msg.commandId}/ack`, {
            method: 'POST',
            headers: { 'X-Device-Token': dev.deviceToken },
          }).catch(() => {})
        }
      }
      if (command === 'RELOAD') {
        ack()
        setTimeout(() => window.location.reload(), 300)
      } else if (command === 'CLEAR_CACHE') {
        ack()
        await dmRef.current.clearAll()
        await refreshConfig()
      } else if (command === 'SCREENSHOT') {
        ack()
        captureScreenshot(dev, msg.commandId)
      }
    },
    [refreshConfig],
  )

  // ---------------- websocket ----------------
  // Open the STOMP over WebSocket connection once paired: subscribe to this
  // screen's topic for pushed updates (schedule/playlist/layout changes and
  // COMMANDs), and start the 30s heartbeat interval. reconnectDelay makes
  // stompjs re-dial automatically after a drop.
  useEffect(() => {
    if (!device) return undefined
    const client = new Client({
      brokerURL: WS_URL,
      reconnectDelay: 5000,
      connectHeaders: { 'x-device-token': device.deviceToken },
    })
    client.onConnect = () => {
      wsConnectedRef.current = true
      client.subscribe(`/topic/screen/${device.screenId}`, (message) => {
        let msg
        try {
          msg = JSON.parse(message.body)
        } catch {
          return
        }
        if (msg.type === 'SCHEDULES_UPDATED' || msg.type === 'PLAYLIST_UPDATED' || msg.type === 'LAYOUT_UPDATED') {
          refreshConfig()
        } else if (msg.type === 'COMMAND') {
          handleCommand(msg)
        }
      })
      sendHeartbeat()
    }
    client.onWebSocketClose = () => {
      wsConnectedRef.current = false
    }
    client.activate()
    stompRef.current = client
    sendHeartbeat()
    const interval = setInterval(sendHeartbeat, HEARTBEAT_MS)
    return () => {
      clearInterval(interval)
      client.deactivate()
      stompRef.current = null
      wsConnectedRef.current = false
    }
  }, [device, sendHeartbeat, refreshConfig, handleCommand])

  // ---------------- proof-of-play flusher ----------------
  // Background uploader for queued playback logs; the effect's cleanup
  // function is the flusher's own stop() handle.
  useEffect(() => {
    if (!device) return undefined
    return startLogFlusher(device.deviceToken)
  }, [device])

  const onLog = useCallback((entry) => queueLog(entry), [])
  const onNowPlaying = useCallback((item) => {
    nowPlayingRef.current = item
  }, [])

  // ---------------- render ----------------
  // Pick the view for the current state: pairing -> connecting -> layout or
  // playlist playback -> "preparing" while downloads finish -> idle.
  let body
  if (!device) {
    body = <PairingScreen code={pairCode} expired={pairState.expired} error={pairState.error} />
  } else if (!config) {
    body = <IdleScreen screenName={device.screenName} note="Connecting…" />
  } else if (activeSchedule?.contentType === 'LAYOUT' && activeSchedule.layout) {
    body = (
      <LayoutRenderer
        layout={activeSchedule.layout}
        scheduleId={activeSchedule.id}
        dm={dm}
        downloadState={downloadState}
        onLog={onLog}
        onNowPlaying={onNowPlaying}
      />
    )
  } else if (activeSchedule?.playlist?.items?.length > 0) {
    const items = activeSchedule.playlist.items
    const anyPlayable = items.some((it) =>
      it.itemType === 'MEDIA' ? it.media && dm.isDownloaded(String(it.media.id)) : navigator.onLine,
    )
    body = anyPlayable ? (
      <PlaylistPlayer
        items={items}
        context={{ scheduleId: activeSchedule.id, playlistId: activeSchedule.playlist.id }}
        dm={dm}
        onLog={onLog}
        onNowPlaying={onNowPlaying}
      />
    ) : (
      <PreparingScreen screenName={device.screenName} downloadState={downloadState} />
    )
  } else {
    body = <IdleScreen screenName={device.screenName} />
  }

  return <div className={`player-root${cursorHidden ? ' cursor-hidden' : ''}`}>{body}</div>
}

// Best-effort screenshot for the SCREENSHOT command: draws the currently
// visible <video> frame or <img> onto a canvas (browsers can't snapshot
// arbitrary DOM without extra libraries) and POSTs it as base64 JPEG.
async function captureScreenshot(device, commandId = null) {
  if (!device) return
  try {
    const target = document.querySelector('.player-root')
    const canvas = document.createElement('canvas')
    canvas.width = window.innerWidth
    canvas.height = window.innerHeight
    const ctx = canvas.getContext('2d')
    ctx.fillStyle = '#000'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    // draw the current video frame or image if present; DOM CSS isn't capturable without extra libs
    const video = target?.querySelector('video')
    const img = target?.querySelector('img')
    const el = video?.offsetParent != null ? video : img?.offsetParent != null ? img : video || img
    if (el) {
      const w = el.videoWidth || el.naturalWidth || canvas.width
      const h = el.videoHeight || el.naturalHeight || canvas.height
      const scale = Math.min(canvas.width / w, canvas.height / h)
      const dw = w * scale
      const dh = h * scale
      ctx.drawImage(el, (canvas.width - dw) / 2, (canvas.height - dh) / 2, dw, dh)
    } else {
      ctx.fillStyle = '#0C0C18'
      ctx.fillRect(0, 0, canvas.width, canvas.height)
      ctx.fillStyle = '#8B5CF6'
      ctx.font = 'bold 40px sans-serif'
      ctx.textAlign = 'center'
      ctx.fillText('Idle / non-capturable content', canvas.width / 2, canvas.height / 2)
    }
    const dataUrl = canvas.toDataURL('image/jpeg', 0.7)
    await fetch(`${API_BASE}/api/player/screenshot`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Device-Token': device.deviceToken },
      body: JSON.stringify({ imageBase64: dataUrl, commandId }),
    })
  } catch {
    /* screenshots are best-effort */
  }
}
