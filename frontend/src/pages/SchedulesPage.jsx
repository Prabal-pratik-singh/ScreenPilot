import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { Plus, CalendarClock, Pause, Play, Trash2, Pencil, MonitorPlay, ListVideo, LayoutPanelTop } from 'lucide-react'
import { api } from '../api/client'
import { Card, PageHeader, Skeleton, EmptyState, Badge, ConfirmDialog } from '../components/ui'
import { fmtIST } from '../lib/format'
import { useAuth, hasRole } from '../auth/AuthContext'

const STATUS_TONE = { ACTIVE: 'success', PAUSED: 'warning', UPCOMING: 'ink', EXPIRED: 'danger' }

export function describeTiming(s) {
  const time = s.allDay ? 'All day' : `${String(s.startTime).slice(0, 5)}–${String(s.endTime).slice(0, 5)} IST`
  const days = s.daysOfWeek?.length
    ? s.daysOfWeek.map((d) => d[0] + d.slice(1).toLowerCase()).join(', ')
    : 'every day'
  let dates = ''
  if (s.dateFrom && s.dateTo) dates = `, ${s.dateFrom} → ${s.dateTo}`
  else if (s.dateFrom) dates = `, from ${s.dateFrom}`
  else if (s.dateTo) dates = `, until ${s.dateTo}`
  return `${time}, ${days}${dates}`
}

export default function SchedulesPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [toDelete, setToDelete] = useState(null)
  const [statusFilter, setStatusFilter] = useState('')

  const schedules = useQuery({ queryKey: ['schedules'], queryFn: () => api.get('/schedules').then((r) => r.data) })
  const canEdit = hasRole(user, 'CONTENT_MANAGER')

  const pauseMutation = useMutation({
    mutationFn: ({ id, action }) => api.post(`/schedules/${id}/${action}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['schedules'] }),
  })
  const deleteMutation = useMutation({
    mutationFn: (id) => api.delete(`/schedules/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['schedules'] })
      setToDelete(null)
    },
  })

  const list = (schedules.data || []).filter((s) => !statusFilter || s.status === statusFilter)

  return (
    <div>
      <PageHeader
        title="Schedules"
        subtitle="What plays where, and when — all times IST"
        actions={
          canEdit && (
            <button className="btn-primary" onClick={() => navigate('/schedules/new')}>
              <Plus size={16} /> New schedule
            </button>
          )
        }
      />

      <div className="flex gap-2 mb-4">
        {['', 'ACTIVE', 'UPCOMING', 'PAUSED', 'EXPIRED'].map((s) => (
          <button
            key={s || 'all'}
            onClick={() => setStatusFilter(s)}
            className={
              statusFilter === s
                ? 'rounded-full bg-ink-800 text-white px-4 py-1.5 text-xs font-bold'
                : 'rounded-full bg-white border border-ink-200 text-ink-500 px-4 py-1.5 text-xs font-semibold hover:bg-ink-50'
            }
          >
            {s || 'All'}
          </button>
        ))}
      </div>

      {schedules.isLoading ? (
        <div className="space-y-3">{[...Array(4)].map((_, i) => <Skeleton key={i} className="h-24 w-full" />)}</div>
      ) : list.length === 0 ? (
        <Card>
          <EmptyState
            icon={CalendarClock}
            title={schedules.data?.length ? 'No schedules with this status' : 'Nothing scheduled yet'}
            hint="Create a schedule to put a playlist or layout on your screens. Screens with nothing scheduled show the branded idle screen."
            action={canEdit && (
              <button className="btn-primary" onClick={() => navigate('/schedules/new')}>
                <Plus size={16} /> New schedule
              </button>
            )}
          />
        </Card>
      ) : (
        <div className="space-y-3">
          {list.map((s) => (
            <Card key={s.id} className="p-5 flex flex-wrap items-center gap-4">
              <div className={`rounded-xl p-3 ${s.contentType === 'PLAYLIST' ? 'bg-ink-800 text-marigold' : 'bg-marigold-100 text-marigold-800'}`}>
                {s.contentType === 'PLAYLIST' ? <ListVideo size={20} /> : <LayoutPanelTop size={20} />}
              </div>
              <div className="flex-1 min-w-[220px]">
                <div className="flex items-center gap-2 flex-wrap">
                  <p className="font-bold text-ink-800">{s.name}</p>
                  <Badge tone={STATUS_TONE[s.status] || 'ink'}>{s.status}</Badge>
                </div>
                <p className="text-sm text-ink-500 mt-0.5">
                  {s.contentType === 'PLAYLIST' ? (
                    <Link className="text-marigold-700 font-semibold hover:underline" to={`/playlists/${s.playlistId}`}>
                      {s.playlistName}
                    </Link>
                  ) : (
                    <span className="font-semibold">{s.layoutName || 'Layout'}</span>
                  )}
                  <span className="text-ink-400"> · {describeTiming(s)}</span>
                </p>
                <p className="text-xs text-ink-400 mt-1 flex items-center gap-1">
                  <MonitorPlay size={12} /> {s.screens.length} screen{s.screens.length === 1 ? '' : 's'}
                  <span className="text-ink-300">
                    — {s.screens.slice(0, 3).map((x) => x.name).join(', ')}{s.screens.length > 3 ? '…' : ''}
                  </span>
                </p>
              </div>
              <div className="text-right text-xs text-ink-300 hidden md:block">
                <p>by {s.createdByName || '—'}</p>
                <p>{fmtIST(s.createdAt, false)}</p>
              </div>
              {canEdit && (
                <div className="flex gap-1.5">
                  <button className="btn-ghost !p-2.5" title="Edit" onClick={() => navigate(`/schedules/${s.id}/edit`)}>
                    <Pencil size={15} />
                  </button>
                  {s.status !== 'EXPIRED' &&
                    (s.active ? (
                      <button className="btn-ghost !p-2.5 text-warning-700" title="Pause"
                        onClick={() => pauseMutation.mutate({ id: s.id, action: 'pause' })}>
                        <Pause size={15} />
                      </button>
                    ) : (
                      <button className="btn-ghost !p-2.5 text-success-700" title="Resume"
                        onClick={() => pauseMutation.mutate({ id: s.id, action: 'resume' })}>
                        <Play size={15} />
                      </button>
                    ))}
                  <button className="btn-ghost !p-2.5 text-danger" title="Delete" onClick={() => setToDelete(s)}>
                    <Trash2 size={15} />
                  </button>
                </div>
              )}
            </Card>
          ))}
        </div>
      )}

      <ConfirmDialog
        open={!!toDelete}
        onClose={() => setToDelete(null)}
        onConfirm={() => deleteMutation.mutate(toDelete.id)}
        busy={deleteMutation.isPending}
        title="Delete schedule"
        message={`Delete "${toDelete?.name}"? Screens using it will switch to their next best schedule, or the idle screen.`}
      />
    </div>
  )
}
