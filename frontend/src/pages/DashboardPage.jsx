// Dashboard: four KPI stat cards, the dark screen map, a network-activity
// area chart with side stats, the groups/locations tree, the worst offline
// screen, and a gradient promo card. Live data comes from the existing
// dashboard/screens/reports/schedules endpoints (missing metrics render as
// zeros — see TODO-BACKEND.md); the portal WebSocket keeps counts fresh.
import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import {
  Monitor, Wifi, WifiOff, AlarmClock, ChevronRight, ChevronDown, ArrowUp,
  Clock, Play, Calendar, ArrowRight, MonitorPlay,
} from 'lucide-react'
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import clsx from 'clsx'
import { api } from '../api/client'
import { Card, SectionHeader, Skeleton, EmptyState, IconTile } from '../components/ui'
import ScreensMap from '../components/ScreensMap'

// ---------- small pieces ----------

// Tinted "↑ x%" pill; deltas are placeholders until the API has historicals.
function DeltaPill({ value = '0%', tone = 'success' }) {
  const tones = {
    success: 'bg-success/15 text-success-400',
    danger: 'bg-danger/15 text-danger',
    warning: 'bg-warning/15 text-warning',
  }
  return (
    <span className={clsx('inline-flex items-center gap-0.5 rounded-full px-2 py-0.5 text-xs font-medium', tones[tone])}>
      <ArrowUp size={11} /> {value}
    </span>
  )
}

// KPI card: 44px tinted icon tile, 13px label, 28px value, caption + delta.
// `highlight` (4th card) lightens the card and tints the border amber.
function StatCard({ icon, tone, label, value, caption, loading, highlight = false }) {
  return (
    <Card className={clsx('p-5', highlight && 'bg-[#14121C] border-warning/25')}>
      <div className="flex items-start gap-4">
        <IconTile icon={icon} tone={tone} />
        <div className="min-w-0 flex-1">
          <p className="text-[13px] text-txt-secondary">{label}</p>
          {loading ? (
            <Skeleton className="mt-1 h-8 w-16" />
          ) : (
            <p className="text-[28px] font-bold leading-tight text-txt-primary tabular-nums">{value}</p>
          )}
        </div>
      </div>
      <div className="mt-3 flex items-end justify-between">
        <p className="text-xs text-txt-muted">{caption}</p>
        <DeltaPill tone={tone === 'danger' ? 'danger' : tone === 'warning' ? 'warning' : 'success'} />
      </div>
    </Card>
  )
}

// Up-to-four 8px dots summarizing a tree node's screens: green online,
// rose offline (amber/stale would need per-node staleness from the API).
function NodeDots({ online, offline }) {
  const dots = []
  for (let i = 0; i < Math.min(online, 4); i++) dots.push('#4ADE80')
  for (let i = 0; i < Math.min(offline, 4 - dots.length); i++) dots.push('#F43F5E')
  return (
    <span className="flex items-center gap-1">
      {dots.map((c, i) => (
        <span key={i} className="h-2 w-2 rounded-full" style={{ background: c, boxShadow: `0 0 5px ${c}80` }} />
      ))}
    </span>
  )
}

// Recursive row of the locations tree (State -> City); 40px rows, chevron
// rotates when open, per-node status dots, children indented 16px.
function TreeNode({ node, depth = 0, onSelect, defaultOpen = false }) {
  const [open, setOpen] = useState(defaultOpen)
  const hasChildren = node.children?.length > 0
  return (
    <div>
      <div
        className="flex h-10 cursor-pointer select-none items-center gap-2 rounded-btn px-2 transition-colors hover:bg-hover"
        style={{ paddingLeft: `${depth * 16 + 8}px` }}
        onClick={() => (hasChildren ? setOpen(!open) : onSelect(node))}
      >
        {hasChildren ? (
          <ChevronDown
            size={14}
            className={clsx('text-txt-muted transition-transform duration-150', !open && '-rotate-90')}
          />
        ) : (
          <span className="w-[14px]" />
        )}
        <span className="flex-1 truncate text-sm text-txt-primary">{node.label}</span>
        <NodeDots online={node.online} offline={node.offline} />
        <ChevronRight size={14} className="text-txt-muted" />
      </div>
      {open &&
        node.children?.map((child) => (
          <TreeNode key={child.key} node={child} depth={depth + 1} onSelect={onSelect} />
        ))}
    </div>
  )
}

// "2d 3h 12m" duration for the longest-offline row.
function fmtDurationDHM(seconds) {
  if (seconds == null || seconds < 0) return '—'
  const d = Math.floor(seconds / 86400)
  const h = Math.floor((seconds % 86400) / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  if (d > 0) return `${d}d ${h}h ${m}m`
  if (h > 0) return `${h}h ${m}m`
  return `${m}m`
}

// Dark tooltip for the activity chart.
function ChartTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null
  return (
    <div className="card px-3 py-2 text-xs">
      <p className="font-medium text-txt-primary">{label}</p>
      <p className="text-txt-secondary">{payload[0].value} plays</p>
    </div>
  )
}

// One row of the activity side list: tinted 28px tile, label, value, delta.
function ActivityStat({ icon, tone, label, value, valueClass }) {
  return (
    <div className="flex items-center gap-3">
      <IconTile icon={icon} tone={tone} size={28} iconSize={14} />
      <span className="flex-1 text-[13px] text-txt-secondary">{label}</span>
      <span className={clsx('text-sm font-semibold tabular-nums', valueClass)}>{value}</span>
      <DeltaPill tone={tone === 'warning' ? 'warning' : 'success'} />
    </div>
  )
}

// Inline SVG illustration for the promo card: media-player card + cloud.
function PromoIllustration() {
  return (
    <svg width="120" height="96" viewBox="0 0 120 96" fill="none" aria-hidden="true" className="shrink-0">
      {/* cloud behind */}
      <path
        d="M84 34a16 16 0 0 1 16 16c8 0 14 6 14 13s-6 13-14 13H62c-9 0-16-7-16-16 0-8 6-15 14-16a24 24 0 0 1 24-10Z"
        fill="rgba(255,255,255,0.14)"
      />
      {/* media player card */}
      <rect x="10" y="18" width="72" height="56" rx="10" fill="rgba(255,255,255,0.22)" />
      <rect x="10" y="18" width="72" height="56" rx="10" stroke="rgba(255,255,255,0.35)" />
      {/* play button */}
      <circle cx="46" cy="40" r="12" fill="rgba(255,255,255,0.9)" />
      <path d="M43 34.5v11l9-5.5-9-5.5Z" fill="#7C3AED" />
      {/* progress bar */}
      <rect x="20" y="60" width="52" height="5" rx="2.5" fill="rgba(255,255,255,0.3)" />
      <rect x="20" y="60" width="30" height="5" rx="2.5" fill="#E879F9" />
    </svg>
  )
}

// ---------- page ----------

function isoDaysAgo(n) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date(Date.now() - n * 86400000))
}

export default function DashboardPage() {
  const navigate = useNavigate()
  const from = isoDaysAgo(6)
  const to = isoDaysAgo(0)

  const stats = useQuery({ queryKey: ['dashboard', 'stats'], queryFn: () => api.get('/dashboard/stats').then((r) => r.data) })
  const tree = useQuery({ queryKey: ['dashboard', 'tree'], queryFn: () => api.get('/dashboard/tree').then((r) => r.data) })
  const screens = useQuery({ queryKey: ['screens', 'all'], queryFn: () => api.get('/screens').then((r) => r.data) })
  // 7-day activity, reusing the reports endpoints (no dedicated metrics API)
  const pop = useQuery({
    queryKey: ['dashboard', 'activity', from, to],
    queryFn: () => api.get(`/reports/proof-of-play?from=${from}&to=${to}`).then((r) => r.data),
  })
  const uptime = useQuery({
    queryKey: ['dashboard', 'uptime', from, to],
    queryFn: () => api.get(`/reports/uptime?from=${from}&to=${to}`).then((r) => r.data),
  })
  const schedules = useQuery({ queryKey: ['schedules'], queryFn: () => api.get('/schedules').then((r) => r.data) })

  // chart series: plays per IST day, labelled "21 Jul"
  const series = useMemo(
    () =>
      (pop.data?.playsPerDay || []).map((p) => ({
        label: new Date(p.label).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' }),
        value: p.value,
      })),
    [pop.data],
  )
  const maxV = Math.max(60, ...series.map((s) => s.value))
  const avgUptime = useMemo(() => {
    const rows = uptime.data?.rows || []
    if (!rows.length) return null
    return Math.round(rows.reduce((sum, r) => sum + (r.avgPct || 0), 0) / rows.length)
  }, [uptime.data])

  // worst offender: offline screen silent the longest (never-seen sorts first)
  const worstOffline = useMemo(() => {
    const list = (screens.data || []).filter((s) => s.status === 'OFFLINE')
    if (!list.length) return null
    return [...list].sort((a, b) => {
      const ta = a.lastHeartbeatAt ? new Date(a.lastHeartbeatAt).getTime() : 0
      const tb = b.lastHeartbeatAt ? new Date(b.lastHeartbeatAt).getTime() : 0
      return ta - tb
    })[0]
  }, [screens.data])
  const worstOfflineSeconds = worstOffline?.lastHeartbeatAt
    ? (Date.now() - new Date(worstOffline.lastHeartbeatAt).getTime()) / 1000
    : null

  const onTreeSelect = (node) => {
    const parts = node.key.split('/')
    const params = new URLSearchParams()
    if (parts[0]) params.set('state', parts[0])
    if (parts[1]) params.set('city', parts[1])
    if (parts[2]) params.set('search', parts[2])
    navigate(`/screens?${params.toString()}`)
  }

  return (
    <div className="grid grid-cols-12 gap-5">
      {/* row 1 — KPI cards */}
      <div className="col-span-12 sm:col-span-6 xl:col-span-3">
        <StatCard icon={Monitor} tone="primary" label="Total Screens" value={stats.data?.total ?? '—'} caption="Across all locations" loading={stats.isLoading} />
      </div>
      <div className="col-span-12 sm:col-span-6 xl:col-span-3">
        <StatCard icon={Wifi} tone="success" label="Online" value={stats.data?.online ?? '—'} caption="Screens online" loading={stats.isLoading} />
      </div>
      <div className="col-span-12 sm:col-span-6 xl:col-span-3">
        <StatCard icon={WifiOff} tone="danger" label="Offline" value={stats.data?.offline ?? '—'} caption="Screens offline" loading={stats.isLoading} />
      </div>
      <div className="col-span-12 sm:col-span-6 xl:col-span-3">
        <StatCard icon={AlarmClock} tone="warning" label="Offline > 24h" value={stats.data?.offlineOver24h ?? '—'} caption="Screens offline" loading={stats.isLoading} highlight />
      </div>

      {/* row 2 — left column (span 8) */}
      <div className="col-span-12 xl:col-span-8 space-y-5">
        <Card className="p-5">
          <SectionHeader title="Screen map" />
          {screens.isLoading ? <Skeleton className="h-[460px] w-full" /> : <ScreensMap screens={screens.data || []} height={460} />}
        </Card>

        <Card className="p-5">
          <SectionHeader
            title={
              <>
                Network activity <span className="ml-1 font-normal text-txt-secondary">(last 7 days)</span>
              </>
            }
          />
          <div className="flex flex-col gap-6 lg:flex-row">
            <div className="h-56 flex-1 min-w-0">
              {pop.isLoading ? (
                <Skeleton className="h-full w-full" />
              ) : (
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={series} margin={{ top: 8, right: 8, left: -22, bottom: 0 }}>
                    <defs>
                      <linearGradient id="activityFill" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stopColor="#A855F7" stopOpacity={0.3} />
                        <stop offset="100%" stopColor="#A855F7" stopOpacity={0} />
                      </linearGradient>
                      {/* soft purple glow under the line */}
                      <filter id="lineGlow" x="-20%" y="-40%" width="140%" height="180%">
                        <feGaussianBlur stdDeviation="4" result="blur" />
                        <feMerge>
                          <feMergeNode in="blur" />
                          <feMergeNode in="SourceGraphic" />
                        </feMerge>
                      </filter>
                    </defs>
                    <CartesianGrid stroke="rgba(148,163,184,0.08)" vertical={false} />
                    <XAxis dataKey="label" tick={{ fill: '#64748B', fontSize: 11 }} tickLine={false} axisLine={false} />
                    <YAxis
                      tick={{ fill: '#64748B', fontSize: 11 }}
                      tickLine={false}
                      axisLine={false}
                      ticks={maxV <= 60 ? [0, 20, 40, 60] : undefined}
                      domain={[0, maxV <= 60 ? 60 : 'auto']}
                    />
                    <Tooltip content={<ChartTooltip />} cursor={{ stroke: 'rgba(168,85,247,0.3)' }} />
                    <Area
                      type="monotone"
                      dataKey="value"
                      stroke="#A855F7"
                      strokeWidth={2.5}
                      fill="url(#activityFill)"
                      filter="url(#lineGlow)"
                      dot={false}
                      activeDot={{ r: 4, fill: '#A855F7', stroke: '#10101E', strokeWidth: 2 }}
                    />
                  </AreaChart>
                </ResponsiveContainer>
              )}
            </div>
            <div className="w-full lg:w-[230px] shrink-0 space-y-4 lg:pt-2">
              <ActivityStat icon={Clock} tone="success" label="Uptime" value={avgUptime != null ? `${avgUptime}%` : '—'} valueClass="text-success-400" />
              <ActivityStat icon={Play} tone="info" label="Media Plays" value={pop.data?.totalPlays ?? '—'} valueClass="text-info" />
              <ActivityStat icon={Calendar} tone="warning" label="Schedules" value={schedules.data?.length ?? '—'} valueClass="text-warning" />
            </div>
          </div>
        </Card>
      </div>

      {/* row 2 — right column (span 4) */}
      <div className="col-span-12 xl:col-span-4 space-y-5">
        <Card className="p-5">
          <SectionHeader
            title="Groups & locations"
            aside={
              <Link to="/screens" className="btn-ghost !h-7 !px-3 !text-xs">
                View all
              </Link>
            }
          />
          {tree.isLoading ? (
            <div className="space-y-2">
              {[...Array(4)].map((_, i) => (
                <Skeleton key={i} className="h-9 w-full" />
              ))}
            </div>
          ) : tree.data?.length ? (
            <div className="-mx-1 max-h-72 overflow-y-auto">
              {tree.data.map((node) => (
                <TreeNode key={node.key} node={node} onSelect={onTreeSelect} defaultOpen={node.label === 'Jharkhand'} />
              ))}
            </div>
          ) : (
            <EmptyState icon={MonitorPlay} title="No screens yet" hint="Pair your first screen from the Screens page." />
          )}
        </Card>

        <Card className="p-5">
          <SectionHeader
            title="Longest offline"
            aside={
              <Link to="/reports" className="text-xs font-medium text-primary-400 hover:text-primary-500 transition-colors">
                View report
              </Link>
            }
          />
          {screens.isLoading ? (
            <Skeleton className="h-16 w-full" />
          ) : worstOffline ? (
            <Link
              to={`/screens/${worstOffline.id}`}
              className="card-inner flex items-center gap-3 p-3 transition-colors hover:border-danger/30 block"
            >
              <IconTile icon={AlarmClock} tone="danger" size={40} iconSize={18} />
              <div className="min-w-0">
                <p className="truncate text-sm font-medium text-txt-primary">{worstOffline.name}</p>
                <p className="text-[13px] text-danger mt-0.5">
                  {worstOfflineSeconds != null ? `Offline since ${fmtDurationDHM(worstOfflineSeconds)}` : 'Never seen online'}
                </p>
              </div>
            </Link>
          ) : (
            <p className="text-sm text-txt-secondary">Every screen is online. 🎉</p>
          )}
        </Card>

        {/* promo card */}
        <div
          className="relative overflow-hidden rounded-card p-6"
          style={{ background: 'linear-gradient(135deg,#6D28D9,#A21CAF)' }}
        >
          <div
            className="pointer-events-none absolute -right-8 -top-10 h-44 w-44 rounded-full"
            style={{ background: 'radial-gradient(circle, rgba(255,255,255,0.22), transparent 70%)' }}
          />
          <div className="relative flex items-start gap-4">
            <div className="min-w-0 flex-1">
              <h3 className="text-lg font-semibold text-white">Manage your content</h3>
              <p className="mt-1.5 text-[13px] leading-relaxed text-white/80">
                Upload, organize and schedule content across all screens.
              </p>
              <button
                onClick={() => navigate('/media')}
                className="mt-4 inline-flex h-9 items-center gap-2 rounded-btn bg-[#7C3AED] px-4 text-sm font-medium text-white shadow-lg shadow-black/20 transition-all hover:brightness-110 active:scale-[0.98]"
              >
                Go to Media <ArrowRight size={15} />
              </button>
            </div>
            <PromoIllustration />
          </div>
        </div>
      </div>
    </div>
  )
}
