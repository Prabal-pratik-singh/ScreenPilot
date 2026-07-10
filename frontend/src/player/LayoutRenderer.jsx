import { useEffect, useState } from 'react'
import PlaylistPlayer from './PlaylistPlayer'
import { mediaFileUrl } from '../lib/media'
import { Cloud } from 'lucide-react'

/**
 * Renders a multi-zone layout: zones are absolutely positioned by their
 * stored percentage rects. Media zones run their own playlist loop.
 */

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

function WidgetZone({ config }) {
  const widget = config?.widget || 'CLOCK'
  return (
    <div
      className="h-full w-full flex flex-col items-center justify-center gap-1 text-white"
      style={{ background: config?.bgColor || '#16233F', color: config?.textColor || '#FFFFFF' }}
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

function TickerZone({ config }) {
  const messages = config?.messages?.length ? config.messages : ['Welcome']
  const speed = Math.max(5, Number(config?.speed) || 30) // seconds per loop
  const text = messages.join('      •      ')
  return (
    <div
      className="h-full w-full overflow-hidden flex items-center"
      style={{ background: config?.bgColor || '#F6A821', color: config?.textColor || '#16233F' }}
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

function LogoZone({ config, dm }) {
  const [url, setUrl] = useState(null)
  useEffect(() => {
    let cancelled = false
    const resolve = async () => {
      if (config?.mediaId) {
        const cached = await dm?.getUrl(String(config.mediaId))
        if (!cancelled) setUrl(cached || mediaFileUrl(config.mediaId))
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
        <span className="text-marigold font-bold" style={{ fontSize: 'min(4vh, 55%)' }}>screenPilot</span>
      </div>
    )
  }
  return <img src={url} alt="logo" className="h-full w-full object-contain p-1" />
}

function WebZone({ config }) {
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    setFailed(false)
  }, [config?.url])
  if (!config?.url || failed) {
    return (
      <div className="h-full w-full bg-ink-900 flex items-center justify-center text-white/40 text-sm">
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
              <div className="h-full w-full bg-ink-900 flex items-center justify-center text-white/30 text-sm">
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
