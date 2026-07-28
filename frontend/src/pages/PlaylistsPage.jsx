// Playlist list page: card grid of every playlist with item count, loop
// duration and last-updated time. "New playlist" creates via a modal and
// jumps straight into the PlaylistBuilder; delete uses a ConfirmDialog.
// All server calls go through TanStack Query mutations that invalidate the
// ['playlists'] cache on success. Editing requires CONTENT_MANAGER role.
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, Link } from 'react-router-dom'
import { Plus, ListVideo, Clock, Trash2 } from 'lucide-react'
import { api, errorMessage } from '../api/client'
import { Card, PageHeader, Skeleton, EmptyState, Modal, Field, Spinner, ConfirmDialog } from '../components/ui'
import { fmtSeconds, timeAgo } from '../lib/format'
import { useAuth, hasRole } from '../auth/AuthContext'

export default function PlaylistsPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState(null)
  const [toDelete, setToDelete] = useState(null)

  const playlists = useQuery({ queryKey: ['playlists'], queryFn: () => api.get('/playlists').then((r) => r.data) })
  const canEdit = hasRole(user, 'CONTENT_MANAGER')

  const createMutation = useMutation({
    mutationFn: () => api.post('/playlists', { name, description }).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['playlists'] })
      navigate(`/playlists/${data.id}`)
    },
    onError: (err) => setError(errorMessage(err)),
  })

  const deleteMutation = useMutation({
    mutationFn: (id) => api.delete(`/playlists/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['playlists'] })
      setToDelete(null)
    },
  })

  return (
    <div>
      <PageHeader
        title="Playlists"
        subtitle="Ordered loops of media and external content"
        actions={
          canEdit && (
            <button className="btn-primary" onClick={() => setCreateOpen(true)}>
              <Plus size={16} /> New playlist
            </button>
          )
        }
      />

      {playlists.isLoading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {[...Array(3)].map((_, i) => <Skeleton key={i} className="h-36 w-full" />)}
        </div>
      ) : !playlists.data?.length ? (
        <Card>
          <EmptyState
            icon={ListVideo}
            title="No playlists yet"
            hint="A playlist is a loop of videos, images, PDFs and links that plays on your screens."
            action={canEdit && (
              <button className="btn-primary" onClick={() => setCreateOpen(true)}><Plus size={16} /> New playlist</button>
            )}
          />
        </Card>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {playlists.data.map((p) => (
            <Card key={p.id} className="p-5 card-lift flex flex-col">
              <div className="flex items-start justify-between">
                <div className="rounded-xl bg-card-inner text-primary-400 p-2.5">
                  <ListVideo size={20} />
                </div>
                {canEdit && (
                  <button className="btn-ghost !p-2 text-danger" title="Delete" onClick={() => setToDelete(p)}>
                    <Trash2 size={14} />
                  </button>
                )}
              </div>
              <Link to={`/playlists/${p.id}`} className="font-bold text-txt-primary mt-3 hover:text-primary-400">
                {p.name}
              </Link>
              <p className="text-sm text-txt-secondary mt-0.5 flex-1">{p.description || 'No description'}</p>
              <div className="flex items-center gap-4 mt-3 text-xs font-semibold text-txt-secondary">
                <span>{p.itemCount} item{p.itemCount === 1 ? '' : 's'}</span>
                <span className="flex items-center gap-1"><Clock size={13} className="text-txt-muted" /> {fmtSeconds(p.totalDurationSeconds)} loop</span>
                <span className="ml-auto text-txt-muted font-normal">updated {timeAgo(p.updatedAt)}</span>
              </div>
            </Card>
          ))}
        </div>
      )}

      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title="New playlist">
        <form onSubmit={(e) => { e.preventDefault(); createMutation.mutate() }} className="space-y-4">
          <Field label="Name">
            <input className="input" required autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="Diwali Offers Loop" />
          </Field>
          <Field label="Description">
            <textarea className="input" rows={2} value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Festive promos for entrance screens" />
          </Field>
          {error && <div className="rounded-lg bg-danger/15 text-danger text-sm px-3 py-2">{error}</div>}
          <div className="flex justify-end gap-2">
            <button type="button" className="btn-ghost" onClick={() => setCreateOpen(false)}>Cancel</button>
            <button type="submit" className="btn-primary" disabled={createMutation.isPending}>
              {createMutation.isPending ? <Spinner className="h-4 w-4" /> : 'Create & open builder'}
            </button>
          </div>
        </form>
      </Modal>

      <ConfirmDialog
        open={!!toDelete}
        onClose={() => setToDelete(null)}
        onConfirm={() => deleteMutation.mutate(toDelete.id)}
        busy={deleteMutation.isPending}
        title="Delete playlist"
        message={`Delete "${toDelete?.name}"? Screens scheduled with it will fall back to other content.`}
      />
    </div>
  )
}
