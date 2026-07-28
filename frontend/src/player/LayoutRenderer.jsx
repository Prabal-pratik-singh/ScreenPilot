import { useEffect, useState } from 'react'
import PlaylistPlayer from './PlaylistPlayer'
import { Cloud } from 'lucide-react'

/**
 * Renders a multi-zone layout: zones are absolutely positioned by their
 * stored percentage rects. Media zones run their own playlist loop.
 */

// Live digital clock in IST, ticking every second.
function ZoneClock({ withSeconds = true }) {
  const [now, setNow] = useState(new Date())
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(t)
  }, [])
  const time = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata', hour: '2-digit', minute: '2-digit',
    ...(withSeconds ? { second: '2-digit' } : {}), hour12: false,
  }).format(now)
  return <p className="font-bold tabular-nums" style={{ fontSize: 'min(9vh, 100%)' }}>{time}</p>
}

// Today's date in IST; refreshes every 30s (dates change rarely).
function ZoneDate() {
  const [now, setNow] = useState(new Date())
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 30000)
    return () => clearInterval(t)
  }, [])
  const date = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata', weekday: 'long', day: '2-digit', month: 'long', year: 'numeric',
  }).format(now)
  return <p className="font-semibold" style={{ fontSize: 'min(4vh, 60%)' }}>{date}</p>
}

// WIDGET zone: clock, date or (placeholder) weather, per zone config.
function WidgetZone({ config }) {
  const widget = config?.widget || 'CLOCK'
  return (
    <div
      className="h-full w-full flex flex-col items-center justify-center gap-1 text-white"
      style={{ background: config?.bgColor || '#0C0C18', color: config?.textColor || '#FFFFFF' }}
    >
      {widget === 'CLOCK' && (
        <>
          <ZoneClock />
          <p className="text-white/50 text-xs uppercase tracking-widest">IST</p>
        </>
      )}
      {widget === 'DATE' && <ZoneDate />}
      {widget === 'WEATHER' && (
        <div className="flex items-center gap-3">
          <Cloud className="opacity-70" size={36} />
          <div>
            <p className="font-bold text-xl">31°C</p>
            <p className="text-xs opacity-60">{config?.city || 'Weather'} · placeholder</p>
          </div>
        </div>
      )}
    </div>
  )
}

// TICKER zone: scrolling text band. The text is rendered twice back-to-back
// so the CSS keyframe loop appears continuous with no gap.
function TickerZone({ config }) {
  const messages = config?.messages?.length ? config.messages : ['Welcome']
  const speed = Math.max(5, Number(config?.speed) || 30) // seconds per loop
  const text = messages.join('      •      ')
  return (
    <div
      className="h-full w-full overflow-hidden flex items-center"
      style={{ background: config?.bgColor || '#7C3AED', color: config?.textColor || '#FFFFFF' }}
    >
      <div
        className="whitespace-nowrap font-bold flex"
        style={{ animation: `ticker-scroll ${speed}s linear infinite`, fontSize: 'min(5vh, 70%)' }}
      >
        <span className="pr-24">{text}</span>
        <span className="pr-24">{text}</span>
      </div>
    </div>
  )
}

// LOGO zone: shows a cached logo image from the download manager, falling
// back to the text wordmark until the blob is available.
function LogoZone({ config, dm }) {
  const [url, setUrl] = useState(null)
  useEffect(() => {
    let cancelled = false
    const resolve = async () => {
      if (config?.mediaId) {
        // logo media is part of requiredMedia, so the download manager caches
        // it; until then the wordmark fallback renders (no unsigned direct URL)
        const cached = await dm?.getUrl(String(config.mediaId))
        if (!cancelled && cached) setUrl(cached)
      }
    }
    resolve()
    return () => {
      cancelled = true
    }
  }, [config?.mediaId, dm])
  if (!url) {
    return (
      <div className="h-full w-full flex items-center justify-center">
        <span className="text-primary-400 font-bold" style={{ fontSize: 'min(4vh, 55%)' }}>screenPilot</span>
      </div>
    )
  }
  return <img src={url} alt="logo" className="h-full w-full object-contain p-1" />
}

// WEB zone: embeds an external page in a sandboxed iframe.
function WebZone({ config }) {
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    setFailed(false)
  }, [config?.url])
  if (!config?.url || failed) {
    return (
      <div className="h-full w-full bg-black/70 flex items-center justify-center text-white/40 text-sm">
        {failed ? 'Page failed to load' : 'No URL configured'}
      </div>
    )
  }
  return (
    <iframe
      title="web-zone"
      src={config.url}
      className="h-full w-full border-0 bg-white"
      sandbox="allow-scripts allow-same-origin allow-forms"
      onError={() => setFailed(true)}
    />
  )
}

// Lays every zone out with absolute positioning (x/y/w/h are percentages of
// the screen) and picks the right zone component per type. Only the first
// MEDIA zone reports "now playing" so the status overlay isn't duplicated.
export default function LayoutRenderer({ layout, scheduleId, dm, onLog, onNowPlaying }) {
  const zones = layout?.zones || []
  let firstMediaZoneId = null
  for (const z of zones) {
    if (z.type === 'MEDIA' && z.playlist?.items?.length) {
      firstMediaZoneId = z.id
      break
    }
  }
  return (
    <div className="absolute inset-0 bg-black overflow-hidden">
      {zones.map((zone) => (
        <div
          key={zone.id}
          className="absolute overflow-hidden"
          style={{
            left: `${zone.x}%`,
            top: `${zone.y}%`,
            width: `${zone.w}%`,
            height: `${zone.h}%`,
            zIndex: zone.z || 1,
          }}
        >
          {zone.type === 'MEDIA' &&
            (zone.playlist?.items?.length ? (
              <div className="relative h-full w-full">
                <PlaylistPlayer
                  items={zone.playlist.items}
                  context={{ scheduleId, playlistId: zone.playlist.id }}
                  dm={dm}
                  onLog={onLog}
                  onNowPlaying={zone.id === firstMediaZoneId ? onNowPlaying : undefined}
                />
              </div>
            ) : (
              <div className="h-full w-full bg-black/70 flex items-center justify-center text-white/30 text-sm">
                No playlist assigned
              </div>
            ))}
          {zone.type === 'TICKER' && <TickerZone config={zone.config} />}
          {zone.type === 'WIDGET' && <WidgetZone config={zone.config} />}
          {zone.type === 'LOGO' && <LogoZone config={zone.config} dm={dm} />}
          {zone.type === 'WEB' && <WebZone config={zone.config} />}
        </div>
      ))}
    </div>
  )
}
