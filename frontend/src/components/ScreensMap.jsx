import { MapContainer, TileLayer, Marker, Popup } from 'react-leaflet'
import MarkerClusterGroup from 'react-leaflet-cluster'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { Link } from 'react-router-dom'
import { StatusDot } from './ui'
import { offlineFor, timeAgo } from '../lib/format'

function statusIcon(status) {
  const color = status === 'ONLINE' ? '#22C55E' : '#EF4444'
  return L.divIcon({
    className: '',
    html: `<div style="width:16px;height:16px;border-radius:9999px;background:${color};border:2.5px solid white;box-shadow:0 1px 4px rgba(22,35,63,.45)"></div>`,
    iconSize: [16, 16],
    iconAnchor: [8, 8],
  })
}

function clusterIcon(cluster) {
  const children = cluster.getAllChildMarkers()
  const offline = children.filter((m) => m.options.status === 'OFFLINE').length
  const bg = offline === 0 ? '#22C55E' : offline === children.length ? '#EF4444' : '#F59E0B'
  return L.divIcon({
    className: '',
    html: `<div style="width:34px;height:34px;border-radius:9999px;background:${bg};color:white;display:flex;align-items:center;justify-content:center;font-weight:700;font-size:13px;border:3px solid white;box-shadow:0 1px 6px rgba(22,35,63,.4)">${children.length}</div>`,
    iconSize: [34, 34],
    iconAnchor: [17, 17],
  })
}

export default function ScreensMap({ screens, height = 420 }) {
  const withCoords = (screens || []).filter((s) => s.latitude != null && s.longitude != null)
  return (
    <div style={{ height }} className="relative rounded-xl overflow-hidden">
      <MapContainer
        center={[24.2, 85.9]}
        zoom={6}
        style={{ height: '100%', width: '100%' }}
        scrollWheelZoom
      >
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <MarkerClusterGroup chunkedLoading iconCreateFunction={clusterIcon} maxClusterRadius={45}>
          {withCoords.map((s) => (
            <Marker
              key={s.id}
              position={[s.latitude, s.longitude]}
              icon={statusIcon(s.status)}
              status={s.status}
            >
              <Popup>
                <div className="text-sm min-w-[190px]">
                  <p className="font-bold text-ink-800">{s.name}</p>
                  <p className="text-ink-500">{s.storeName}</p>
                  <p className="text-ink-400 text-xs">{s.city}, {s.state}</p>
                  <div className="flex items-center gap-1.5 mt-2">
                    <StatusDot status={s.status} />
                    {s.status === 'ONLINE' ? (
                      <span className="text-success-700 font-semibold text-xs">Online</span>
                    ) : (
                      <span className="text-danger-700 font-semibold text-xs">Offline for {offlineFor(s)}</span>
                    )}
                  </div>
                  <p className="text-xs text-ink-400 mt-1">Last seen: {timeAgo(s.lastHeartbeatAt)}</p>
                  <Link to={`/screens/${s.id}`} className="text-xs font-semibold text-marigold-700 hover:underline mt-1 inline-block">
                    Open screen →
                  </Link>
                </div>
              </Popup>
            </Marker>
          ))}
        </MarkerClusterGroup>
      </MapContainer>
      <div className="absolute bottom-3 left-3 z-[1000] card px-3 py-2 flex items-center gap-4 text-xs font-medium text-ink-600">
        <span className="flex items-center gap-1.5"><StatusDot status="ONLINE" /> Online</span>
        <span className="flex items-center gap-1.5"><StatusDot status="OFFLINE" /> Offline</span>
        <span className="flex items-center gap-1.5"><StatusDot status="WARNING" /> Mixed cluster</span>
      </div>
    </div>
  )
}
