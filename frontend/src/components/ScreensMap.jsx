// Dark network map for the dashboard, built on Leaflet (react-leaflet) with
// the free CARTO dark basemap. Screens are clustered BY CITY into circular
// badges whose ring + glow color reflects the aggregate status: all online =
// green, all offline = rose, mixed = amber. Includes custom dark zoom/locate
// controls (default Leaflet control is disabled) and a blurred legend pill.
import { useMemo } from 'react'
import { MapContainer, TileLayer, Marker, useMap } from 'react-leaflet'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { Plus, Minus, Locate } from 'lucide-react'

const CENTER = [23.8, 85.5] // East India frame (Bihar/Jharkhand/Bengal/Odisha)
const ZOOM = 6.5

const STATUS_COLORS = {
  success: '#4ADE80',
  danger: '#F43F5E',
  warning: '#F59E0B',
}

// Group screens into one badge per city, averaging coordinates and
// classifying the aggregate status from the online/offline mix.
function clusterByCity(screens) {
  const byCity = new Map()
  for (const s of screens) {
    if (s.latitude == null || s.longitude == null) continue
    const key = s.city || 'Unknown'
    if (!byCity.has(key)) byCity.set(key, { city: key, lat: 0, lng: 0, count: 0, online: 0 })
    const c = byCity.get(key)
    c.lat += s.latitude
    c.lng += s.longitude
    c.count += 1
    if (s.status === 'ONLINE') c.online += 1
  }
  return [...byCity.values()].map((c) => {
    const status = c.online === c.count ? 'success' : c.online === 0 ? 'danger' : 'warning'
    return { ...c, lat: c.lat / c.count, lng: c.lng / c.count, status }
  })
}

// 34px circular divIcon: dark fill, 2px status ring, soft glow, white count.
function badgeIcon(cluster) {
  const color = STATUS_COLORS[cluster.status]
  return L.divIcon({
    className: '',
    html: `<div style="width:34px;height:34px;border-radius:9999px;background:rgba(12,12,24,0.92);border:2px solid ${color};box-shadow:0 0 14px ${color}66;display:flex;align-items:center;justify-content:center;color:#F1F5F9;font-weight:600;font-size:13px;font-family:Inter,system-ui,sans-serif">${cluster.count}</div>`,
    iconSize: [34, 34],
    iconAnchor: [17, 17],
  })
}

// Stacked square dark buttons replacing the default zoom control.
function MapControls() {
  const map = useMap()
  const btn =
    'flex h-8 w-8 items-center justify-center rounded-lg border border-subtle bg-card text-txt-secondary hover:text-txt-primary hover:border-primary-500/40 transition-colors'
  return (
    <div className="absolute left-3 top-3 z-[1000] flex flex-col gap-1.5">
      <button type="button" className={btn} aria-label="Zoom in" onClick={() => map.zoomIn()}>
        <Plus size={15} />
      </button>
      <button type="button" className={btn} aria-label="Zoom out" onClick={() => map.zoomOut()}>
        <Minus size={15} />
      </button>
      <button type="button" className={btn} aria-label="Reset view" onClick={() => map.setView(CENTER, ZOOM)}>
        <Locate size={15} />
      </button>
    </div>
  )
}

// One dot + label chip inside the legend pill.
function LegendChip({ color, label }) {
  return (
    <span className="flex items-center gap-1.5 text-xs text-txt-secondary">
      <span className="h-2 w-2 rounded-full" style={{ background: color, boxShadow: `0 0 6px ${color}99` }} />
      {label}
    </span>
  )
}

export default function ScreensMap({ screens, height = 460 }) {
  const clusters = useMemo(() => clusterByCity(screens || []), [screens])
  return (
    <div style={{ height }} className="relative overflow-hidden rounded-tile border border-subtle">
      <MapContainer
        center={CENTER}
        zoom={ZOOM}
        zoomControl={false}
        style={{ height: '100%', width: '100%', background: '#07070E' }}
        scrollWheelZoom
      >
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> &copy; <a href="https://carto.com/attributions">CARTO</a>'
          url="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
        />
        {clusters.map((c) => (
          <Marker
            key={c.city}
            position={[c.lat, c.lng]}
            icon={badgeIcon(c)}
            eventHandlers={{
              // clicking a city badge zooms into that city
              click: (e) => e.target._map.setView([c.lat, c.lng], 10),
            }}
          />
        ))}
        <MapControls />
      </MapContainer>

      {/* blurred legend pill, bottom-left inside the map */}
      <div className="absolute bottom-3 left-3 z-[1000] flex items-center gap-4 rounded-full bg-black/40 px-4 py-2 backdrop-blur-md border border-white/10">
        <LegendChip color={STATUS_COLORS.success} label="Online" />
        <LegendChip color={STATUS_COLORS.danger} label="Offline" />
        <LegendChip color={STATUS_COLORS.warning} label="Mixed cluster" />
      </div>
    </div>
  )
}
