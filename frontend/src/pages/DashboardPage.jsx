// Landing page after login: headline stat cards (total/online/offline),
// the live screen map, a collapsible state -> city -> group tree, and the
// screens that have been offline longest. Data comes from three TanStack
// Query fetches and stays fresh via the portal WebSocket cache updates.
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useState } from 'react'
import { MonitorPlay, Wifi, WifiOff, AlarmClock, ChevronRight, ChevronDown } from 'lucide-react'
import clsx from 'clsx'
import { api } from '../api/client'
import { Card, PageHeader, Skeleton, StatusDot, EmptyState } from '../components/ui'
import ScreensMap from '../components/ScreensMap'
import { offlineFor } from '../lib/format'

// One headline number with an icon; shows a skeleton while loading.
function StatCard({ icon: Icon, label, value, tone, loading }) {
  const tones = {
    ink: 'bg-ink-50 text-ink-600',
    success: 'bg-success-100 text-success-700',
    danger: 'bg-danger-100 text-danger-700',
    warning: 'bg-warning-100 text-warning-700',
  }
  return (
    <Card className="p-5 flex items-center gap-4">
      <div className={clsx('rounded-xl p-3', tones[tone])}>
        <Icon size={22} />
      </div>
      <div>
        {loading ? (
          <Skeleton className="h-7 w-14 mb-1" />
        ) : (
          <p className="text-2xl font-bold text-ink-800 leading-tight">{value}</p>
        )}
        <p className="text-xs font-medium text-ink-400">{label}</p>
      </div>
    </Card>
  )
}

// Recursive row of the locations tree: expands/collapses its children and
// shows per-node online/offline counts; clicking a leaf filters the Screens page.
function TreeNode({ node, depth = 0, onSelect }) {
  const [open, setOpen] = useState(depth === 0)
  const hasChildren = node.children?.length > 0
  return (
    <div>
      <div
        className="flex items-center gap-1.5 rounded-lg px-2 py-1.5 hover:bg-ink-50 cursor-pointer select-none"
        style={{ paddingLeft: `${depth * 18 + 8}px` }}
        onClick={() => (hasChildren ? setOpen(!open) : onSelect(node))}
      >
        {hasChildren ? (
          <button
            className="text-ink-300"
            onClick={(e) => {
              e.stopPropagation()
              setOpen(!open)
            }}
          >
            {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          </button>
        ) : (
          <span className="w-[14px]" />
        )}
        <button
          className="flex-1 text-left text-sm font-medium text-ink-700 hover:text-ink-900 truncate"
          onClick={(e) => {
            e.stopPropagation()
            onSelect(node)
          }}
          title="Filter screens"
        >
          {node.label}
        </button>
        <span className="flex items-center gap-1 text-xs font-semibold text-success-700">
          <StatusDot status="ONLINE" className="h-2 w-2" /> {node.online}
        </span>
        <span className="flex items-center gap-1 text-xs font-semibold text-danger-700">
          <StatusDot status="OFFLINE" className="h-2 w-2" /> {node.offline}
        </span>
      </div>
      {open &&
        node.children?.map((child) => (
          <TreeNode key={child.key} node={child} depth={depth + 1} onSelect={onSelect} />
        ))}
    </div>
  )
}

export default function DashboardPage() {
  const navigate = useNavigate()
  const stats = useQuery({ queryKey: ['dashboard', 'stats'], queryFn: () => api.get('/dashboard/stats').then((r) => r.data) })
  const tree = useQuery({ queryKey: ['dashboard', 'tree'], queryFn: () => api.get('/dashboard/tree').then((r) => r.data) })
  const screens = useQuery({ queryKey: ['screens', 'all'], queryFn: () => api.get('/screens').then((r) => r.data) })

  // Oldest heartbeat first = longest offline; show at most 8.
  const longestOffline = (screens.data || [])
    .filter((s) => s.status === 'OFFLINE')
    .sort((a, b) => {
      const ta = a.lastHeartbeatAt ? new Date(a.lastHeartbeatAt).getTime() : 0
      const tb = b.lastHeartbeatAt ? new Date(b.lastHeartbeatAt).getTime() : 0
      return ta - tb
    })
    .slice(0, 8)

  // Tree node key is "state/city/group"; turn it into Screens page filters.
  const onTreeSelect = (node) => {
    const parts = node.key.split('/')
    const params = new URLSearchParams()
    if (parts[0]) params.set('state', parts[0])
    if (parts[1]) params.set('city', parts[1])
    if (parts[2]) params.set('search', parts[2])
    navigate(`/screens?${params.toString()}`)
  }

  return (
    <div>
      <PageHeader title="Dashboard" subtitle="Network overview — live status of every screen" />

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
        <StatCard icon={MonitorPlay} label="Total screens" value={stats.data?.total ?? '—'} tone="ink" loading={stats.isLoading} />
        <StatCard icon={Wifi} label="Online" value={stats.data?.online ?? '—'} tone="success" loading={stats.isLoading} />
        <StatCard icon={WifiOff} label="Offline" value={stats.data?.offline ?? '—'} tone="danger" loading={stats.isLoading} />
        <StatCard icon={AlarmClock} label="Offline > 24h" value={stats.data?.offlineOver24h ?? '—'} tone="warning" loading={stats.isLoading} />
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-3 gap-4">
        <Card className="xl:col-span-2 p-4">
          <h2 className="font-bold text-ink-800 mb-3 px-1">Screen map</h2>
          {screens.isLoading ? (
            <Skeleton className="h-[420px] w-full" />
          ) : (
            <ScreensMap screens={screens.data || []} />
          )}
        </Card>

        <div className="space-y-4">
          <Card className="p-4">
            <h2 className="font-bold text-ink-800 mb-2 px-1">Groups &amp; locations</h2>
            {tree.isLoading ? (
              <div className="space-y-2 p-1">
                <Skeleton className="h-6 w-full" />
                <Skeleton className="h-6 w-4/5" />
                <Skeleton className="h-6 w-3/5" />
              </div>
            ) : tree.data?.length ? (
              <div className="-mx-1 max-h-72 overflow-y-auto">
                {tree.data.map((node) => (
                  <TreeNode key={node.key} node={node} onSelect={onTreeSelect} />
                ))}
              </div>
            ) : (
              <EmptyState title="No screens yet" hint="Pair your first screen from the Screens page." />
            )}
          </Card>

          <Card className="p-4">
            <h2 className="font-bold text-ink-800 mb-2 px-1">Longest offline</h2>
            {screens.isLoading ? (
              <div className="space-y-2 p-1">
                <Skeleton className="h-9 w-full" />
                <Skeleton className="h-9 w-full" />
                <Skeleton className="h-9 w-full" />
              </div>
            ) : longestOffline.length ? (
              <ul className="divide-y divide-ink-100/70">
                {longestOffline.map((s) => (
                  <li
                    key={s.id}
                    className="flex items-center gap-3 py-2 px-1 cursor-pointer hover:bg-ink-50 rounded-lg"
                    onClick={() => navigate(`/screens/${s.id}`)}
                  >
                    <StatusDot status="OFFLINE" />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-semibold text-ink-700 truncate">{s.name}</p>
                      <p className="text-xs text-ink-400 truncate">{s.city}, {s.state}</p>
                    </div>
                    <span className="text-xs font-bold text-danger-700 whitespace-nowrap">
                      {s.lastHeartbeatAt ? offlineFor(s) : 'never seen'}
                    </span>
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState title="Everything is online" hint="No offline screens right now." />
            )}
          </Card>
        </div>
      </div>
    </div>
  )
}
