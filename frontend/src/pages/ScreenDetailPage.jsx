// Single-screen detail page (/screens/:id): live "now playing" card, the
// schedules and per-media download status reported by the player's
// heartbeats, device details, storage gauge, and an admin remote-control
// panel (reload / clear cache / screenshot) with recent command history.
import { useState } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowLeft, Pencil, Trash2, MonitorPlay, MapPin, HardDrive, CalendarClock, Download,
  RefreshCw, Eraser, Camera, TerminalSquare,
} from 'lucide-react'
import { api, API_BASE, errorMessage } from '../api/client'
import { Card, StatusDot, Badge, Skeleton, Modal, Field, Spinner, ConfirmDialog } from '../components/ui'
import { offlineFor, timeAgo, fmtIST } from '../lib/format'
import { useAuth, hasRole } from '../auth/AuthContext'
import { describeTiming } from './SchedulesPage'

const DL_BADGE = {
  downloaded: { tone: 'success', label: 'Downloaded' },
  downloading: { tone: 'warning', label: 'Downloading' },
  failed: { tone: 'danger', label: 'Failed' },
  pending: { tone: 'ink', label: 'Pending' },
}

// Look one media id up in the mediaState summary the player heartbeats send
// (cached / downloading / failed buckets) and map it to a badge status.
function downloadStatusOf(mediaId, mediaState) {
  if (!mediaState) return 'pending'
  const id = String(mediaId)
  if ((mediaState.cached || []).map(String).includes(id)) return 'downloaded'
  const dl = (mediaState.downloading || []).find((d) => String(d.id) === id)
  if (dl) return 'downloading'
  if ((mediaState.failed || []).map(String).includes(id)) return 'failed'
  return 'pending'
}

// Shows what this screen is meant to play: its active schedules with the
// playlist items, plus the download state of every required media file.
// Refetches every 20s to track progress.
function ContentPanel({ screen }) {
  const content = useQuery({
    queryKey: ['screen', screen.id, 'content'],
    queryFn: () => api.get(`/screens/${screen.id}/content`).then((r) => r.data),
    refetchInterval: 20000,
  })

  if (content.isLoading) return <Skeleton className="h-40 w-full" />
  const schedules = content.data?.schedules || []
  const requiredMedia = content.data?.requiredMedia || []
  const mediaState = screen.mediaState

  return (
    <>
      <Card className="p-5">
        <h2 className="font-bold text-ink-800 mb-3 flex items-center gap-2">
          <CalendarClock size={17} className="text-marigold-600" /> Active schedules
        </h2>
        {schedules.length === 0 ? (
          <p className="text-sm text-ink-400">Nothing scheduled — this screen shows the branded idle screen.</p>
        ) : (
          <div className="space-y-4">
            {schedules.map((s) => (
              <div key={s.id} className="rounded-xl border border-ink-100 p-4">
                <div className="flex items-center gap-2 flex-wrap">
                  <p className="font-semibold text-ink-800">{s.name}</p>
                  <Badge tone={s.allDay ? 'ink' : 'marigold'}>{s.allDay ? 'All day' : 'Timed window'}</Badge>
                  <span className="text-xs text-ink-400">{describeTiming(s)}</span>
                </div>
                {s.playlist && (
                  <ol className="mt-3 space-y-1.5">
                    {s.playlist.items.map((it, i) => (
                      <li key={it.id} className="flex items-center gap-2.5 text-sm">
                        <span className="w-5 text-right text-xs font-bold text-ink-300">{i + 1}.</span>
                        <span className="flex-1 truncate text-ink-700">{it.title || it.url}</span>
                        <span className="text-[11px] uppercase font-bold text-ink-300">
                          {it.itemType === 'MEDIA' ? it.media?.type : it.itemType}
                        </span>
                        <span className="text-xs text-ink-400 w-12 text-right">{Math.round(it.effectiveDurationSeconds)}s</span>
                      </li>
                    ))}
                  </ol>
                )}
                {s.contentType === 'LAYOUT' && (
                  <p className="text-xs text-ink-400 mt-2">Multi-zone layout{s.layout?.name ? `: ${s.layout.name}` : ''}</p>
                )}
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card className="p-5">
        <h2 className="font-bold text-ink-800 mb-3 flex items-center gap-2">
          <Download size={17} className="text-marigold-600" /> Media downloads on this screen
        </h2>
        {/* status legend: pending / downloading / downloaded / failed reported by heartbeats */}
        {requiredMedia.length === 0 ? (
          <p className="text-sm text-ink-400">No media required by the current schedules.</p>
        ) : (
          <div className="space-y-2">
            {requiredMedia.map((m) => {
              const status = downloadStatusOf(m.id, mediaState)
              const cfg = DL_BADGE[status]
              const progress =
                status === 'downloading'
                  ? (mediaState?.downloading || []).find((d) => String(d.id) === String(m.id))?.progress
                  : null
              return (
                <div key={m.id} className="flex items-center gap-3 rounded-lg border border-ink-100/70 px-3 py-2">
                  <span className="flex-1 truncate text-sm text-ink-700">{m.name}</span>
                  <span className="text-[11px] uppercase font-bold text-ink-300">{m.type}</span>
                  {progress != null && <span className="text-xs font-semibold text-warning-700">{progress}%</span>}
                  <Badge tone={cfg.tone}>{cfg.label}</Badge>
                </div>
              )
            })}
            {screen.status !== 'ONLINE' && (
              <p className="text-xs text-ink-400 mt-1">Screen is offline — statuses are from its last heartbeat.</p>
            )}
          </div>
        )}
      </Card>
    </>
  )
}

// Label/value row used in the Device details card.
function InfoRow({ label, children }) {
  return (
    <div className="flex justify-between gap-4 py-2 border-b border-ink-100/60 last:border-0">
      <span className="text-xs font-semibold uppercase tracking-wide text-ink-400 pt-0.5">{label}</span>
      <span className="text-sm text-ink-700 text-right">{children}</span>
    </div>
  )
}

// Remote-control card: admins push RELOAD / CLEAR_CACHE / SCREENSHOT
// commands to the player (delivered over its WebSocket) and see the latest
// screenshot plus a short command history with SENT/ACKED/COMPLETED badges.
function CommandsPanel({ screen, isAdmin }) {
  const queryClient = useQueryClient()
  const [feedback, setFeedback] = useState(null)

  // <img> tags can't send the Authorization header, so the API mints a
  // short-lived HMAC-signed link for the screenshot instead
  const shotLink = useQuery({
    queryKey: ['screen', screen.id, 'screenshot-link'],
    queryFn: () => api.get(`/screens/${screen.id}/screenshot-link`).then((r) => r.data.url),
  })
  const [shotAvailable, setShotAvailable] = useState(true)

  const history = useQuery({
    queryKey: ['screen', screen.id, 'commands'],
    queryFn: () => api.get(`/screens/${screen.id}/commands`).then((r) => r.data),
    refetchInterval: 15000,
  })

  const send = useMutation({
    mutationFn: (command) => api.post(`/screens/${screen.id}/commands`, { command }).then((r) => r.data),
    onSuccess: (data) => {
      setFeedback(`${data.command} sent to the player`)
      queryClient.invalidateQueries({ queryKey: ['screen', screen.id, 'commands'] })
      if (data.command === 'SCREENSHOT') {
        // give the player a moment to capture & upload, then refresh the preview
        setTimeout(() => {
          setShotAvailable(true)
          queryClient.invalidateQueries({ queryKey: ['screen', screen.id, 'screenshot-link'] })
          queryClient.invalidateQueries({ queryKey: ['screen', screen.id, 'commands'] })
        }, 5000)
      }
      setTimeout(() => setFeedback(null), 5000)
    },
    onError: (err) => setFeedback(errorMessage(err)),
  })

  const cmdBadge = { SENT: 'warning', ACKED: 'ink', COMPLETED: 'success' }

  return (
    <Card className="p-5">
      <h2 className="font-bold text-ink-800 mb-3 flex items-center gap-2">
        <TerminalSquare size={17} className="text-marigold-600" /> Remote control
      </h2>
      {isAdmin ? (
        <div className="grid grid-cols-1 gap-2">
          <button className="btn-ghost !justify-start" disabled={send.isPending || screen.status !== 'ONLINE'}
            onClick={() => send.mutate('RELOAD')}>
            <RefreshCw size={15} className="text-ink-400" /> Reload player
          </button>
          <button className="btn-ghost !justify-start" disabled={send.isPending || screen.status !== 'ONLINE'}
            onClick={() => send.mutate('CLEAR_CACHE')}>
            <Eraser size={15} className="text-ink-400" /> Clear cache &amp; re-download
          </button>
          <button className="btn-ghost !justify-start" disabled={send.isPending || screen.status !== 'ONLINE'}
            onClick={() => send.mutate('SCREENSHOT')}>
            <Camera size={15} className="text-ink-400" /> Request screenshot
          </button>
          {screen.status !== 'ONLINE' && (
            <p className="text-xs text-ink-300">Commands need the screen to be online.</p>
          )}
          {feedback && <p className="text-xs font-semibold text-marigold-700">{feedback}</p>}
        </div>
      ) : (
        <p className="text-sm text-ink-400">Admins can reload the player, clear its cache or request a screenshot.</p>
      )}

      <div className="mt-4">
        <p className="text-xs font-semibold uppercase tracking-wide text-ink-400 mb-2">Last screenshot</p>
        {shotAvailable && shotLink.data ? (
          <img
            src={`${API_BASE}${shotLink.data}`}
            alt="Player screenshot"
            className="rounded-lg border border-ink-100 w-full bg-ink-900"
            onError={() => setShotAvailable(false)}
          />
        ) : (
          <p className="text-xs text-ink-300">No screenshot captured yet — request one while the screen is online.</p>
        )}
      </div>

      {history.data?.length > 0 && (
        <div className="mt-4">
          <p className="text-xs font-semibold uppercase tracking-wide text-ink-400 mb-2">Recent commands</p>
          <ul className="space-y-1.5">
            {history.data.slice(0, 5).map((c) => (
              <li key={c.id} className="flex items-center justify-between text-xs">
                <span className="text-ink-600 font-medium">{c.command}</span>
                <span className="flex items-center gap-2">
                  <Badge tone={cmdBadge[c.status] || 'ink'}>{c.status}</Badge>
                  <span className="text-ink-300">{timeAgo(c.createdAt)}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </Card>
  )
}

// Edit form for the screen's metadata (name, store, location, group,
// orientation, resolution, coordinates); PUTs and patches the query cache.
function EditScreenModal({ screen, groups, open, onClose }) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState({
    name: screen.name || '',
    storeName: screen.storeName || '',
    city: screen.city || '',
    state: screen.state || '',
    groupId: screen.group?.id || '',
    orientation: screen.orientation || 'LANDSCAPE',
    resolution: screen.resolution || '',
    latitude: screen.latitude ?? '',
    longitude: screen.longitude ?? '',
  })
  const [error, setError] = useState(null)
  const set = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }))

  const mutation = useMutation({
    mutationFn: (payload) => api.put(`/screens/${screen.id}`, payload).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.setQueryData(['screen', screen.id], data)
      queryClient.invalidateQueries({ queryKey: ['screens'] })
      onClose()
    },
    onError: (err) => setError(errorMessage(err)),
  })

  const submit = (e) => {
    e.preventDefault()
    mutation.mutate({
      name: form.name,
      storeName: form.storeName,
      city: form.city,
      state: form.state,
      groupId: form.groupId || null,
      orientation: form.orientation,
      resolution: form.resolution,
      latitude: form.latitude === '' ? null : Number(form.latitude),
      longitude: form.longitude === '' ? null : Number(form.longitude),
    })
  }

  return (
    <Modal open={open} onClose={onClose} title="Edit screen" wide>
      <form onSubmit={submit} className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <Field label="Screen name"><input className="input" required value={form.name} onChange={set('name')} /></Field>
        <Field label="Store name"><input className="input" value={form.storeName} onChange={set('storeName')} /></Field>
        <Field label="City"><input className="input" value={form.city} onChange={set('city')} /></Field>
        <Field label="State"><input className="input" value={form.state} onChange={set('state')} /></Field>
        <Field label="Screen group">
          <select className="input" value={form.groupId} onChange={set('groupId')}>
            <option value="">No group</option>
            {groups.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
          </select>
        </Field>
        <Field label="Orientation">
          <select className="input" value={form.orientation} onChange={set('orientation')}>
            <option value="LANDSCAPE">Landscape</option>
            <option value="PORTRAIT">Portrait</option>
          </select>
        </Field>
        <Field label="Resolution"><input className="input" value={form.resolution} onChange={set('resolution')} /></Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Latitude"><input className="input" type="number" step="any" value={form.latitude} onChange={set('latitude')} /></Field>
          <Field label="Longitude"><input className="input" type="number" step="any" value={form.longitude} onChange={set('longitude')} /></Field>
        </div>
        {error && <div className="sm:col-span-2 rounded-lg bg-danger-100 text-danger-700 text-sm px-3 py-2">{error}</div>}
        <div className="sm:col-span-2 flex justify-end gap-2">
          <button type="button" className="btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn-primary" disabled={mutation.isPending}>
            {mutation.isPending ? <Spinner className="h-4 w-4" /> : 'Save changes'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

// Page component: loads the screen by route id and assembles the panels.
export default function ScreenDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const [editOpen, setEditOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)

  const screen = useQuery({ queryKey: ['screen', id], queryFn: () => api.get(`/screens/${id}`).then((r) => r.data) })
  const groups = useQuery({ queryKey: ['groups'], queryFn: () => api.get('/groups').then((r) => r.data) })

  const deleteMutation = useMutation({
    mutationFn: () => api.delete(`/screens/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['screens'] })
      navigate('/screens')
    },
  })

  if (screen.isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-9 w-72" />
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          <Skeleton className="h-72 w-full lg:col-span-2" />
          <Skeleton className="h-72 w-full" />
        </div>
      </div>
    )
  }
  if (screen.isError || !screen.data) {
    return (
      <Card className="p-8 text-center">
        <p className="font-semibold text-ink-700">Screen not found or you don't have access.</p>
        <Link to="/screens" className="text-marigold-700 font-semibold text-sm mt-2 inline-block">← Back to screens</Link>
      </Card>
    )
  }

  const s = screen.data
  const isAdmin = hasRole(user, 'ADMIN')
  const storagePct =
    s.storageUsedMb != null && s.storageTotalMb ? Math.min(100, (s.storageUsedMb / s.storageTotalMb) * 100) : null

  return (
    <div>
      <button onClick={() => navigate('/screens')} className="flex items-center gap-1.5 text-sm text-ink-400 hover:text-ink-700 mb-3">
        <ArrowLeft size={15} /> Screens
      </button>

      <div className="flex flex-wrap items-start justify-between gap-3 mb-6">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold text-ink-800">{s.name}</h1>
            <span className="flex items-center gap-1.5">
              <StatusDot status={s.status} pulse />
              {s.status === 'ONLINE' ? (
                <span className="text-sm font-bold text-success-700">Online</span>
              ) : (
                <span className="text-sm font-bold text-danger-700">
                  Offline {s.lastHeartbeatAt ? `for ${offlineFor(s)}` : '(never seen)'}
                </span>
              )}
            </span>
          </div>
          <p className="text-sm text-ink-400 mt-1 flex items-center gap-1.5">
            <MapPin size={13} /> {s.storeName || 'Store not set'} · {s.city || '—'}, {s.state || '—'}
            {s.group && <Badge tone="marigold" className="ml-2">{s.group.name}</Badge>}
          </p>
        </div>
        {isAdmin && (
          <div className="flex gap-2">
            <button className="btn-ghost" onClick={() => setEditOpen(true)}>
              <Pencil size={15} /> Edit
            </button>
            <button className="btn-danger" onClick={() => setDeleteOpen(true)}>
              <Trash2 size={15} /> Delete
            </button>
          </div>
        )}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <div className="lg:col-span-2 space-y-4">
          <Card className="p-5">
            <h2 className="font-bold text-ink-800 mb-3 flex items-center gap-2">
              <MonitorPlay size={17} className="text-marigold-600" /> Now playing
            </h2>
            {s.status === 'ONLINE' ? (
              <div className="flex items-center gap-4">
                <div className="h-20 w-32 rounded-lg bg-ink-800 flex items-center justify-center text-ink-300 text-xs overflow-hidden">
                  {s.currentItemThumbUrl ? (
                    <img
                      src={`${API_BASE}${s.currentItemThumbUrl}`}
                      alt=""
                      className="h-full w-full object-cover"
                      onError={(e) => { e.currentTarget.style.display = 'none' }}
                    />
                  ) : (
                    'No preview'
                  )}
                </div>
                <div>
                  <p className="font-semibold text-ink-800">{s.currentItemName || 'Idle — nothing scheduled'}</p>
                  <p className="text-xs text-ink-400 mt-1">Reported live by the player with each heartbeat.</p>
                </div>
              </div>
            ) : (
              <p className="text-sm text-ink-400">Screen is offline — nothing is being reported.</p>
            )}
          </Card>

          <ContentPanel screen={s} />

          <Card className="p-5">
            <h2 className="font-bold text-ink-800 mb-2">Device details</h2>
            <InfoRow label="Orientation">{s.orientation === 'PORTRAIT' ? 'Portrait (9:16)' : 'Landscape (16:9)'}</InfoRow>
            <InfoRow label="Resolution">{s.resolution || '—'}</InfoRow>
            <InfoRow label="App version">{s.appVersion || '—'}</InfoRow>
            <InfoRow label="Paired">{s.paired ? <Badge tone="success">Paired device</Badge> : <Badge>Not paired</Badge>}</InfoRow>
            <InfoRow label="Coordinates">
              {s.latitude != null && s.longitude != null ? `${s.latitude.toFixed(4)}, ${s.longitude.toFixed(4)}` : '—'}
            </InfoRow>
            <InfoRow label="Last heartbeat">{s.lastHeartbeatAt ? `${timeAgo(s.lastHeartbeatAt)} (${fmtIST(s.lastHeartbeatAt)})` : 'Never'}</InfoRow>
            <InfoRow label="Added on">{fmtIST(s.createdAt)}</InfoRow>
          </Card>
        </div>

        <div className="space-y-4">
          <CommandsPanel screen={s} isAdmin={isAdmin} />
          <Card className="p-5">
            <h2 className="font-bold text-ink-800 mb-3 flex items-center gap-2">
              <HardDrive size={17} className="text-marigold-600" /> Storage
            </h2>
            {storagePct != null ? (
              <>
                <div className="h-2.5 rounded-full bg-ink-100 overflow-hidden">
                  <div
                    className={storagePct > 90 ? 'h-full bg-danger' : storagePct > 70 ? 'h-full bg-warning' : 'h-full bg-success'}
                    style={{ width: `${storagePct}%` }}
                  />
                </div>
                <p className="text-xs text-ink-400 mt-2">
                  {s.storageUsedMb?.toFixed(0)} MB of {s.storageTotalMb?.toFixed(0)} MB used
                </p>
              </>
            ) : (
              <p className="text-sm text-ink-400">Not reported yet.</p>
            )}
          </Card>
        </div>
      </div>

      {editOpen && <EditScreenModal screen={s} groups={groups.data || []} open={editOpen} onClose={() => setEditOpen(false)} />}
      <ConfirmDialog
        open={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        onConfirm={() => deleteMutation.mutate()}
        busy={deleteMutation.isPending}
        title="Delete screen"
        message={`"${s.name}" will be removed permanently. The paired player will stop receiving content.`}
      />
    </div>
  )
}
