import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, Link } from 'react-router-dom'
import { Plus, LayoutPanelTop, Trash2, Monitor, Smartphone } from 'lucide-react'
import clsx from 'clsx'
import { api, errorMessage } from '../api/client'
import { Card, PageHeader, Skeleton, EmptyState, Modal, Field, Spinner, ConfirmDialog, Badge } from '../components/ui'
import { timeAgo } from '../lib/format'
import { useAuth, hasRole } from '../auth/AuthContext'

const PRESETS = [
  { key: 'FULLSCREEN', label: 'Fullscreen', desc: 'One media zone', rects: [[0, 0, 100, 100, 'M']] },
  { key: 'SPLIT_70_30', label: '70 / 30 split', desc: 'Main + side zone', rects: [[0, 0, 70, 100, 'M'], [70, 0, 30, 100, 'M']] },
  { key: 'SPLIT_50_50', label: '50 / 50 split', desc: 'Two equal zones', rects: [[0, 0, 50, 100, 'M'], [50, 0, 50, 100, 'M']] },
  { key: 'L_SHAPE', label: 'L-shape', desc: 'Main + sidebar + ticker', rects: [[0, 0, 75, 87.5, 'M'], [75, 0, 25, 87.5, 'W'], [0, 87.5, 100, 12.5, 'T']] },
]

function PresetThumb({ rects, className }) {
  return (
    <div className={clsx('relative bg-ink-900 rounded-md overflow-hidden', className)} style={{ aspectRatio: '16/9' }}>
      {rects.map(([x, y, w, h, t], i) => (
        <div
          key={i}
          className={clsx(
            'absolute border border-ink-900/60 flex items-center justify-center text-[8px] font-bold',
            t === 'M' ? 'bg-ink-600 text-ink-200' : t === 'T' ? 'bg-marigold text-ink-900' : 'bg-ink-700 text-marigold',
          )}
          style={{ left: `${x}%`, top: `${y}%`, width: `${w}%`, height: `${h}%` }}
        >
          {t === 'M' ? 'MEDIA' : t === 'T' ? 'TICKER' : 'WIDGET'}
        </div>
      ))}
    </div>
  )
}

function ZonePreview({ layout }) {
  const colors = { MEDIA: 'bg-ink-600', TICKER: 'bg-marigold', WIDGET: 'bg-ink-700', LOGO: 'bg-ink-500', WEB: 'bg-ink-400' }
  return (
    <div
      className="relative bg-ink-900 rounded-lg overflow-hidden w-full"
      style={{ aspectRatio: layout.orientation === 'PORTRAIT' ? '9/16' : '16/9', maxHeight: layout.orientation === 'PORTRAIT' ? 180 : undefined }}
    >
      {layout.zones.map((z) => (
        <div
          key={z.id}
          className={clsx('absolute border border-ink-900/70', colors[z.type] || 'bg-ink-600')}
          style={{ left: `${z.x}%`, top: `${z.y}%`, width: `${z.w}%`, height: `${z.h}%` }}
        />
      ))}
    </div>
  )
}

export default function LayoutsPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [name, setName] = useState('')
  const [orientation, setOrientation] = useState('LANDSCAPE')
  const [preset, setPreset] = useState('FULLSCREEN')
  const [error, setError] = useState(null)
  const [toDelete, setToDelete] = useState(null)
  const [deleteError, setDeleteError] = useState(null)

  const layouts = useQuery({ queryKey: ['layouts'], queryFn: () => api.get('/layouts').then((r) => r.data) })
  const canEdit = hasRole(user, 'CONTENT_MANAGER')

  const createMutation = useMutation({
    mutationFn: () => api.post('/layouts', { name, orientation, preset }).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['layouts'] })
      navigate(`/layouts/${data.id}`)
    },
    onError: (err) => setError(errorMessage(err)),
  })

  const deleteMutation = useMutation({
    mutationFn: (id) => api.delete(`/layouts/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['layouts'] })
      setToDelete(null)
    },
    onError: (err) => setDeleteError(errorMessage(err)),
  })

  return (
    <div>
      <PageHeader
        title="Layouts"
        subtitle="Multi-zone screen designs — media, tickers, widgets, logos and web zones"
        actions={
          canEdit && (
            <button className="btn-primary" onClick={() => setCreateOpen(true)}>
              <Plus size={16} /> New layout
            </button>
          )
        }
      />

      {layouts.isLoading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {[...Array(3)].map((_, i) => <Skeleton key={i} className="h-48 w-full" />)}
        </div>
      ) : !layouts.data?.length ? (
        <Card>
          <EmptyState
            icon={LayoutPanelTop}
            title="No layouts yet"
            hint="Split the screen into zones: a main media area, a scrolling ticker, a clock, a logo — then schedule it like a playlist."
            action={canEdit && (
              <button className="btn-primary" onClick={() => setCreateOpen(true)}><Plus size={16} /> New layout</button>
            )}
          />
        </Card>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {layouts.data.map((l) => (
            <Card key={l.id} className="p-4 flex flex-col gap-3">
              <Link to={`/layouts/${l.id}`}>
                <ZonePreview layout={l} />
              </Link>
              <div className="flex items-center gap-2">
                <div className="flex-1 min-w-0">
                  <Link to={`/layouts/${l.id}`} className="font-bold text-ink-800 hover:text-marigold-700 truncate block">
                    {l.name}
                  </Link>
                  <p className="text-xs text-ink-400">
                    {l.zoneCount} zone{l.zoneCount === 1 ? '' : 's'} · updated {timeAgo(l.updatedAt)}
                  </p>
                </div>
                <Badge tone="ink">{l.orientation === 'PORTRAIT' ? '9:16' : '16:9'}</Badge>
                {canEdit && (
                  <button className="btn-ghost !p-2 text-danger" title="Delete" onClick={() => { setToDelete(l); setDeleteError(null) }}>
                    <Trash2 size={14} />
                  </button>
                )}
              </div>
            </Card>
          ))}
        </div>
      )}

      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title="New layout" wide>
        <form onSubmit={(e) => { e.preventDefault(); createMutation.mutate() }} className="space-y-5">
          <Field label="Name">
            <input className="input" required autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="Entrance — main + ticker" />
          </Field>
          <Field label="Orientation">
            <div className="grid grid-cols-2 gap-2 max-w-sm">
              <button type="button" onClick={() => setOrientation('LANDSCAPE')}
                className={clsx('rounded-lg border-2 p-3 flex items-center gap-2 text-sm font-semibold', orientation === 'LANDSCAPE' ? 'border-marigold bg-marigold-50' : 'border-ink-200 text-ink-500')}>
                <Monitor size={16} /> Landscape 16:9
              </button>
              <button type="button" onClick={() => setOrientation('PORTRAIT')}
                className={clsx('rounded-lg border-2 p-3 flex items-center gap-2 text-sm font-semibold', orientation === 'PORTRAIT' ? 'border-marigold bg-marigold-50' : 'border-ink-200 text-ink-500')}>
                <Smartphone size={16} /> Portrait 9:16
              </button>
            </div>
          </Field>
          <Field label="Start from a preset">
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              {PRESETS.map((p) => (
                <button
                  key={p.key}
                  type="button"
                  onClick={() => setPreset(p.key)}
                  className={clsx('rounded-xl border-2 p-2.5 text-left', preset === p.key ? 'border-marigold bg-marigold-50' : 'border-ink-100 hover:border-ink-200')}
                >
                  <PresetThumb rects={p.rects} />
                  <p className="text-xs font-bold text-ink-700 mt-2">{p.label}</p>
                  <p className="text-[10px] text-ink-400">{p.desc}</p>
                </button>
              ))}
            </div>
          </Field>
          {error && <div className="rounded-lg bg-danger-100 text-danger-700 text-sm px-3 py-2">{error}</div>}
          <div className="flex justify-end gap-2">
            <button type="button" className="btn-ghost" onClick={() => setCreateOpen(false)}>Cancel</button>
            <button type="submit" className="btn-primary" disabled={createMutation.isPending}>
              {createMutation.isPending ? <Spinner className="h-4 w-4" /> : 'Create & open designer'}
            </button>
          </div>
        </form>
      </Modal>

      <ConfirmDialog
        open={!!toDelete}
        onClose={() => setToDelete(null)}
        onConfirm={() => deleteMutation.mutate(toDelete.id)}
        busy={deleteMutation.isPending}
        title="Delete layout"
        message={`Delete "${toDelete?.name}"?`}
      >
        {deleteError && <div className="mt-3 rounded-lg bg-danger-100 text-danger-700 text-sm px-3 py-2">{deleteError}</div>}
      </ConfirmDialog>
    </div>
  )
}
