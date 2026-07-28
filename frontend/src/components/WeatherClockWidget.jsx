// Sidebar bottom widget: current weather (Open-Meteo, no API key) above a
// live IST clock. Weather is cached in module scope for 15 minutes so
// re-renders and route changes don't refetch; on any error it degrades to
// an em-dash instead of breaking the sidebar.
import { useEffect, useState } from 'react'
import { CloudSun, MapPin } from 'lucide-react'

const WEATHER_URL =
  'https://api.open-meteo.com/v1/forecast?latitude=23.43&longitude=85.32&current=temperature_2m,weather_code'
const CACHE_MS = 15 * 60 * 1000

// simple WMO weather-code -> label mapping
function describe(code) {
  if (code === 0) return 'Clear sky'
  if (code <= 3) return 'Partly cloudy'
  if (code === 45 || code === 48) return 'Foggy'
  if (code <= 57) return 'Drizzle'
  if (code <= 67) return 'Rain'
  if (code <= 77) return 'Snow'
  if (code <= 82) return 'Showers'
  if (code >= 95) return 'Thunderstorm'
  return 'Cloudy'
}

// module-level cache shared by every mount of the widget
let cached = null // { at: epoch-ms, temp, label }

export default function WeatherClockWidget() {
  const [weather, setWeather] = useState(cached)
  const [now, setNow] = useState(new Date())

  // fetch weather once per cache window
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      if (cached && Date.now() - cached.at < CACHE_MS) {
        setWeather(cached)
        return
      }
      try {
        const res = await fetch(WEATHER_URL)
        const data = await res.json()
        const next = {
          at: Date.now(),
          temp: Math.round(data.current?.temperature_2m),
          label: describe(data.current?.weather_code),
        }
        cached = next
        if (!cancelled) setWeather(next)
      } catch {
        if (!cancelled) setWeather(null) // renders the "—" fallback
      }
    }
    load()
    const refresh = setInterval(load, CACHE_MS)
    return () => {
      cancelled = true
      clearInterval(refresh)
    }
  }, [])

  // tick the clock every second
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(t)
  }, [])

  const time = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(now)
  const date = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata',
    weekday: 'short',
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  }).format(now)

  return (
    <div className="card p-4 space-y-3">
      {/* weather row */}
      <div className="flex items-center gap-3">
        <CloudSun size={22} className="text-warning shrink-0" />
        <div className="min-w-0">
          <p className="text-xl font-bold text-txt-primary leading-tight">
            {weather?.temp != null ? `${weather.temp}°C` : '—'}
          </p>
          <p className="text-xs text-txt-secondary truncate">{weather?.label || 'Weather unavailable'}</p>
        </div>
      </div>
      <div className="border-t border-subtle" />
      {/* location row */}
      <div className="flex items-center gap-2 text-xs text-txt-secondary">
        <MapPin size={13} className="text-txt-muted shrink-0" />
        Kanke Road, Ranchi
      </div>
      {/* live IST clock */}
      <div>
        <p className="text-xl font-semibold text-txt-primary tabular-nums leading-tight">{time}</p>
        <p className="text-xs text-txt-muted mt-0.5">{date} · IST</p>
      </div>
    </div>
  )
}
