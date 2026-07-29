// Topbar notification bell with a real dropdown. There is no notifications
// API yet (see TODO-BACKEND.md), so alerts are derived live from data the
// portal already fetches: screens offline (split at the 24h mark), failed
// media downloads reported in heartbeats, and paused schedules. The badge
// shows the number of current issues; clicking an item navigates to it.
import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { Bell, WifiOff, AlarmClock, Download, Pause, CheckCircle2 } from 'lucide-react'
import clsx from 'clsx'
import { api } from '../api/client'
import { IconTile } from './ui'
import { offlineFor } from '../lib/format'

const DAY_MS = 24 * 60 * 60 * 1000

// Build the alert list from cached queries (shared keys = no extra requests
// beyond what the dashboard already loads).
function useNotifications() {
  const screens = useQuery({ queryKey: ['screens', 'all'], queryFn: () => api.get('/screens').then((r) => r.data) })
  const schedules = useQuery({ queryKey: ['schedules'], queryFn: () => api.get('/schedules').then((r) => r.data) })

  return useMemo(() => {
    const items = []
    for (const s of screens.data || []) {
      if (s.status === 'OFFLINE') {
        const overDay = !s.lastHeartbeatAt || Date.now() - new Date(s.lastHeartbeatAt).getTime() > DAY_MS
        items.push({
          id: `off-${s.id}`,
          tone: overDay ? 'danger' : 'warning',
          icon: overDay ? AlarmClock : WifiOff,
          title: s.name,
          detail: s.lastHeartbeatAt ? `Offline for ${offlineFor(s)}` : 'Never seen online',
          to: `/screens/${s.id}`,
          rank: overDay ? 0 : 1,
        })
      }
      const failed = s.mediaState?.failed?.length || 0
      if (failed > 0) {
        items.push({
          id: `dl-${s.id}`,
          tone: 'warning',
          icon: Download,
          title: s.name,
          detail: `${failed} media download${failed > 1 ? 's' : ''} failed`,
          to: `/screens/${s.id}`,
          rank: 1,
        })
      }
    }
    for (const sc of schedules.data || []) {
      if (sc.status === 'PAUSED') {
        items.push({
          id: `sch-${sc.id}`,
          tone: 'info',
          icon: Pause,
          title: sc.name,
          detail: 'Schedule is paused',
          to: '/schedules',
          rank: 2,
        })
      }
    }
    return items.sort((a, b) => a.rank - b.rank).slice(0, 12)
  }, [screens.data, schedules.data])
}

export default function NotificationsMenu() {
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()
  const items = useNotifications()

  return (
    <div className="relative">
      <button
        onClick={() => setOpen((v) => !v)}
        className={clsx(
          'relative h-9 w-9 rounded-btn border border-subtle bg-hover flex items-center justify-center transition-colors',
          open ? 'text-txt-primary border-primary-500/40' : 'text-txt-secondary hover:text-txt-primary',
        )}
        aria-label={`Notifications${items.length ? ` (${items.length})` : ''}`}
      >
        <Bell size={17} />
        {items.length > 0 && (
          <span className="absolute -right-1.5 -top-1.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-grad-primary px-1 text-[10px] font-semibold text-white shadow-glow-primary">
            {items.length > 9 ? '9+' : items.length}
          </span>
        )}
      </button>

      {open && (
        <>
          {/* invisible backdrop: clicking anywhere outside closes the panel
              (z-40/z-50 keep the panel above the Leaflet map's internal panes) */}
          <div className="fixed inset-0 z-40" onClick={() => setOpen(false)} />
          <div className="absolute right-0 z-50 mt-2 w-80 card p-2 animate-pop-in shadow-2xl shadow-black/50">
            <div className="flex items-center justify-between border-b border-subtle px-3 py-2 mb-1">
              <p className="text-sm font-semibold text-txt-primary">Notifications</p>
              <span className="text-xs text-txt-muted">{items.length} active</span>
            </div>
            {items.length === 0 ? (
              <div className="flex flex-col items-center gap-2 py-8 text-center">
                <CheckCircle2 size={26} className="text-success-400" />
                <p className="text-sm text-txt-secondary">All caught up — no issues right now.</p>
              </div>
            ) : (
              <div className="max-h-80 overflow-y-auto">
                {items.map((n) => (
                  <button
                    key={n.id}
                    onClick={() => {
                      setOpen(false)
                      navigate(n.to)
                    }}
                    className="flex w-full items-center gap-3 rounded-btn px-3 py-2.5 text-left transition-colors hover:bg-hover"
                  >
                    <IconTile icon={n.icon} tone={n.tone} size={34} iconSize={15} />
                    <div className="min-w-0">
                      <p className="truncate text-[13px] font-medium text-txt-primary">{n.title}</p>
                      <p
                        className={clsx(
                          'truncate text-xs',
                          n.tone === 'danger' ? 'text-danger' : n.tone === 'warning' ? 'text-warning' : 'text-txt-secondary',
                        )}
                      >
                        {n.detail}
                      </p>
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  )
}
