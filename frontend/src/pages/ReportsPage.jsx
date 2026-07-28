// Reports page with two tabs sharing one IST date range:
//  - Proof of play: how many times each creative ran on each screen
//    (bar chart per day + creative x screen table), for brand reporting.
//  - Screen uptime: per-day online percentage per screen with red flags.
// Charts are drawn with Recharts; Excel/PDF exports stream from the API.
import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  BarChart, Bar, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts'
import { BarChart3, FileSpreadsheet, FileText, AlertTriangle } from 'lucide-react'
import clsx from 'clsx'
import { api, API_BASE, getAccessToken } from '../api/client'
import { Card, PageHeader, Skeleton, EmptyState, Badge } from '../components/ui'
import { fmtIST, fmtSeconds } from '../lib/format'

// Single-series marks: contrast-validated on the dark card surfaces (≥3:1)
const BAR_COLOR = '#A855F7'
const LINE_COLOR = '#38BDF8'
const AXIS_TICK = { fill: '#64748B', fontSize: 11 }

// "YYYY-MM-DD" for n days ago, measured in IST (default range = last 7 days).
function daysAgoISO(n) {
  const d = new Date(Date.now() - n * 86400000)
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(d)
}

// Custom Recharts tooltip styled like the app's cards.
function ChartTooltip({ active, payload, label, unit }) {
  if (!active || !payload?.length) return null
  return (
    <div className="card px-3 py-2 text-xs">
      <p className="font-bold text-txt-primary">{label}</p>
      <p className="text-txt-secondary">{payload[0].value} {unit}</p>
    </div>
  )
}

// Fetch an export with the auth header (a plain link couldn't carry the
// Bearer token), then trigger a browser download via a temporary object URL.
async function downloadExport(report, format, from, to, extraParams = '') {
  const res = await fetch(
    `${API_BASE}/api/reports/export?report=${report}&format=${format}&from=${from}&to=${to}${extraParams}`,
    { headers: { Authorization: `Bearer ${getAccessToken()}` } },
  )
  if (!res.ok) throw new Error('Export failed')
  const blob = await res.blob()
  const cd = res.headers.get('Content-Disposition') || ''
  const name = cd.match(/filename="([^"]+)"/)?.[1] || `${report}.${format}`
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
}

// Excel/PDF export pair; only one export runs at a time.
function ExportButtons({ report, from, to, extraParams }) {
  const [busy, setBusy] = useState(null)
  const run = async (format) => {
    setBusy(format)
    try {
      await downloadExport(report, format, from, to, extraParams)
    } catch {
      /* surfaced by button state reset */
    } finally {
      setBusy(null)
    }
  }
  return (
    <div className="flex gap-2">
      <button className="btn-ghost" disabled={!!busy} onClick={() => run('xlsx')}>
        <FileSpreadsheet size={15} className="text-success-400" /> {busy === 'xlsx' ? 'Exporting…' : 'Excel'}
      </button>
      <button className="btn-ghost" disabled={!!busy} onClick={() => run('pdf')}>
        <FileText size={15} className="text-danger" /> {busy === 'pdf' ? 'Exporting…' : 'PDF'}
      </button>
    </div>
  )
}

// Proof-of-play tab: optional screen/creative filters feed both the report
// query and the export URL; renders stat cards, plays-per-day chart, table.
function ProofOfPlayTab({ from, to }) {
  const screens = useQuery({ queryKey: ['screens', 'all'], queryFn: () => api.get('/screens').then((r) => r.data) })
  const media = useQuery({ queryKey: ['media'], queryFn: () => api.get('/media').then((r) => r.data) })
  const [screenId, setScreenId] = useState('')
  const [mediaId, setMediaId] = useState('')

  const params = useMemo(() => {
    let p = ''
    if (screenId) p += `&screenIds=${screenId}`
    if (mediaId) p += `&mediaIds=${mediaId}`
    return p
  }, [screenId, mediaId])

  const report = useQuery({
    queryKey: ['reports', 'pop', from, to, screenId, mediaId],
    queryFn: () => api.get(`/reports/proof-of-play?from=${from}&to=${to}${params}`).then((r) => r.data),
    enabled: !!from && !!to,
  })

  return (
    <div className="space-y-4">
      <Card className="p-4 flex flex-wrap items-center gap-3">
        <select className="input max-w-[240px]" value={screenId} onChange={(e) => setScreenId(e.target.value)}>
          <option value="">All screens</option>
          {(screens.data || []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
        <select className="input max-w-[240px]" value={mediaId} onChange={(e) => setMediaId(e.target.value)}>
          <option value="">All creatives</option>
          {(media.data || []).map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
        </select>
        <div className="ml-auto">
          <ExportButtons report="proof-of-play" from={from} to={to} extraParams={params} />
        </div>
      </Card>

      {report.isLoading ? (
        <Skeleton className="h-72 w-full" />
      ) : !report.data ? null : (
        <>
          <div className="grid grid-cols-3 gap-4">
            <Card className="p-4 text-center">
              <p className="text-2xl font-bold text-txt-primary">{report.data.totalPlays}</p>
              <p className="text-xs font-medium text-txt-muted">Total plays</p>
            </Card>
            <Card className="p-4 text-center">
              <p className="text-2xl font-bold text-txt-primary">{fmtSeconds(report.data.totalSeconds)}</p>
              <p className="text-xs font-medium text-txt-muted">Time on screen</p>
            </Card>
            <Card className="p-4 text-center">
              <p className="text-2xl font-bold text-txt-primary">{report.data.rows.length}</p>
              <p className="text-xs font-medium text-txt-muted">Creative × screen rows</p>
            </Card>
          </div>

          <Card className="p-4">
            <h3 className="font-bold text-txt-primary mb-1 px-1">Plays per day</h3>
            <p className="text-xs text-txt-muted px-1 mb-3">Completed plays across the selected screens, IST days</p>
            <div className="h-56">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={report.data.playsPerDay} margin={{ top: 4, right: 8, left: -18, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.08)" vertical={false} />
                  <XAxis dataKey="label" tick={AXIS_TICK} tickLine={false} axisLine={{ stroke: 'rgba(148,163,184,0.15)' }}
                    tickFormatter={(v) => v.slice(5)} />
                  <YAxis tick={AXIS_TICK} tickLine={false} axisLine={false} allowDecimals={false} />
                  <Tooltip content={<ChartTooltip unit="plays" />} cursor={{ fill: 'rgba(168,85,247,0.08)' }} />
                  <Bar dataKey="value" fill={BAR_COLOR} radius={[4, 4, 0, 0]} maxBarSize={38} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </Card>

          <Card>
            {report.data.rows.length === 0 ? (
              <EmptyState
                icon={BarChart3}
                title="No plays recorded in this period"
                hint="Pair a player, schedule a playlist, and completed plays will appear here."
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-xs uppercase tracking-wide text-txt-muted border-b border-subtle">
                      <th className="px-4 py-3">Creative</th>
                      <th className="px-4 py-3">Type</th>
                      <th className="px-4 py-3">Screen</th>
                      <th className="px-4 py-3 text-right">Plays</th>
                      <th className="px-4 py-3 text-right">Time on screen</th>
                      <th className="px-4 py-3">First played</th>
                      <th className="px-4 py-3">Last played</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-subtle">
                    {report.data.rows.map((r, i) => (
                      <tr key={i} className="hover:bg-hover">
                        <td className="px-4 py-2.5 font-semibold text-txt-primary">{r.creative}</td>
                        <td className="px-4 py-2.5"><Badge>{r.itemType || '—'}</Badge></td>
                        <td className="px-4 py-2.5 text-txt-secondary">{r.screenName}</td>
                        <td className="px-4 py-2.5 text-right font-bold text-txt-primary">{r.playCount}</td>
                        <td className="px-4 py-2.5 text-right text-txt-secondary">{fmtSeconds(r.totalSeconds)}</td>
                        <td className="px-4 py-2.5 text-txt-muted whitespace-nowrap">{fmtIST(r.firstPlayed)}</td>
                        <td className="px-4 py-2.5 text-txt-muted whitespace-nowrap">{fmtIST(r.lastPlayed)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </>
      )}
    </div>
  )
}

// Uptime tab: step line of online screens over time, red-flag chips for
// screens averaging under 90%, and a per-day percentage grid.
function UptimeTab({ from, to }) {
  const report = useQuery({
    queryKey: ['reports', 'uptime', from, to],
    queryFn: () => api.get(`/reports/uptime?from=${from}&to=${to}`).then((r) => r.data),
    enabled: !!from && !!to,
  })

  if (report.isLoading) return <Skeleton className="h-72 w-full" />
  if (!report.data) return null
  const days = report.data.rows[0]?.days || []

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <ExportButtons report="uptime" from={from} to={to} />
      </div>

      <Card className="p-4">
        <h3 className="font-bold text-txt-primary mb-1 px-1">Online screens over time</h3>
        <p className="text-xs text-txt-muted px-1 mb-3">Sampled from heartbeat status history</p>
        <div className="h-56">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={report.data.onlineOverTime} margin={{ top: 4, right: 8, left: -18, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.08)" vertical={false} />
              <XAxis dataKey="label" tick={AXIS_TICK} tickLine={false} axisLine={{ stroke: 'rgba(148,163,184,0.15)' }} minTickGap={40} />
              <YAxis tick={AXIS_TICK} tickLine={false} axisLine={false} allowDecimals={false} />
              <Tooltip content={<ChartTooltip unit="screens online" />} />
              <Line type="stepAfter" dataKey="value" stroke={LINE_COLOR} strokeWidth={2} dot={false} activeDot={{ r: 4, fill: LINE_COLOR, stroke: '#10101E' }} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      </Card>

      {report.data.redFlags.length > 0 && (
        <Card className="p-4 border-l-4 !border-l-danger">
          <h3 className="font-bold text-txt-primary mb-2 flex items-center gap-2">
            <AlertTriangle size={16} className="text-danger" /> Red flags — worst performers (&lt; 90% avg)
          </h3>
          <div className="flex flex-wrap gap-2">
            {report.data.redFlags.map((r) => (
              <span key={r.screenId} className="rounded-lg bg-danger/15 text-danger px-3 py-1.5 text-xs font-bold">
                {r.screenName} — {r.avgPct}%
              </span>
            ))}
          </div>
        </Card>
      )}

      <Card>
        {report.data.rows.length === 0 ? (
          <EmptyState icon={BarChart3} title="No screens to report on" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs uppercase tracking-wide text-txt-muted border-b border-subtle">
                  <th className="px-4 py-3">Screen</th>
                  <th className="px-4 py-3">Store</th>
                  {days.map((d) => (
                    <th key={d.date} className="px-2 py-3 text-right whitespace-nowrap">{d.date.slice(5)}</th>
                  ))}
                  <th className="px-4 py-3 text-right">Avg</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-subtle">
                {report.data.rows.map((r) => (
                  <tr key={r.screenId} className="hover:bg-hover">
                    <td className="px-4 py-2.5 font-semibold text-txt-primary whitespace-nowrap">{r.screenName}</td>
                    <td className="px-4 py-2.5 text-txt-muted whitespace-nowrap">{r.storeName || '—'}</td>
                    {r.days.map((d) => (
                      <td
                        key={d.date}
                        className={clsx(
                          'px-2 py-2.5 text-right text-xs font-semibold',
                          d.pct >= 95 ? 'text-success-400' : d.pct >= 80 ? 'text-warning' : 'text-danger',
                        )}
                      >
                        {d.pct}%
                      </td>
                    ))}
                    <td className="px-4 py-2.5 text-right font-bold text-txt-primary">{r.avgPct}%</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  )
}

// Page shell: tab switcher + shared from/to date inputs.
export default function ReportsPage() {
  const [tab, setTab] = useState('pop')
  const [from, setFrom] = useState(daysAgoISO(6))
  const [to, setTo] = useState(daysAgoISO(0))

  return (
    <div>
      <PageHeader title="Reports" subtitle="Proof-of-play for brands, uptime for operations — all in IST" />

      <div className="flex flex-wrap items-center gap-3 mb-4">
        <div className="flex rounded-xl bg-card-inner border border-subtle p-1">
          <button
            onClick={() => setTab('pop')}
            className={clsx('rounded-lg px-4 py-2 text-sm font-bold', tab === 'pop' ? 'bg-grad-primary text-white' : 'text-txt-secondary')}
          >
            Proof of play
          </button>
          <button
            onClick={() => setTab('uptime')}
            className={clsx('rounded-lg px-4 py-2 text-sm font-bold', tab === 'uptime' ? 'bg-grad-primary text-white' : 'text-txt-secondary')}
          >
            Screen uptime
          </button>
        </div>
        <div className="flex items-center gap-2 ml-auto">
          <input type="date" className="input" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
          <span className="text-txt-muted">→</span>
          <input type="date" className="input" value={to} min={from} onChange={(e) => setTo(e.target.value)} />
        </div>
      </div>

      {tab === 'pop' ? <ProofOfPlayTab from={from} to={to} /> : <UptimeTab from={from} to={to} />}
    </div>
  )
}
