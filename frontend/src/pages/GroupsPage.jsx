// Screen Groups admin page (ADMIN and up). Groups both organise screens and
// scope what non-admin users are allowed to see. Simple CRUD: card grid,
// create/edit modal, delete confirmation — the server rejects deleting a
// group that would leave things orphaned, and the error shows in the dialog.
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, FolderTree, Pencil, Trash2, MonitorPlay } from 'lucide-react'
import { api, errorMessage } from '../api/client'
import { Card, PageHeader, Skeleton, EmptyState, Modal, Field, Spinner, ConfirmDialog } from '../components/ui'

// Create/edit form in a modal; `existing` decides POST vs PUT.
function GroupModal({ open, onClose, existing }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(existing?.name || '')
  const [description, setDescription] = useState(existing?.description || '')
  const [error, setError] = useState(null)

  const mutation = useMutation({
    mutationFn: (payload) =>
      existing ? api.put(`/groups/${existing.id}`, payload).then((r) => r.data) : api.post('/groups', payload).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['groups'] })
      onClose()
    },
    onError: (err) => setError(errorMessage(err)),
  })

  return (
    <Modal open={open} onClose={onClose} title={existing ? 'Edit group' : 'New screen group'}>
      <form
        onSubmit={(e) => {
          e.preventDefault()
          mutation.mutate({ name, description })
        }}
        className="space-y-4"
      >
        <Field label="Group name">
          <input className="input" required value={name} onChange={(e) => setName(e.target.value)} placeholder="Ranchi" />
        </Field>
        <Field label="Description">
          <textarea className="input" rows={2} value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Stores in and around Ranchi" />
        </Field>
        {error && <div className="rounded-lg bg-danger-100 text-danger-700 text-sm px-3 py-2">{error}</div>}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn-primary" disabled={mutation.isPending}>
            {mutation.isPending ? <Spinner className="h-4 w-4" /> : existing ? 'Save' : 'Create group'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

export default function GroupsPage() {
  const queryClient = useQueryClient()
  const [modal, setModal] = useState(null) // null | 'new' | group
  const [toDelete, setToDelete] = useState(null)
  const [deleteError, setDeleteError] = useState(null)

  const groups = useQuery({ queryKey: ['groups'], queryFn: () => api.get('/groups').then((r) => r.data) })

  const deleteMutation = useMutation({
    mutationFn: (id) => api.delete(`/groups/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['groups'] })
      setToDelete(null)
      setDeleteError(null)
    },
    onError: (err) => setDeleteError(errorMessage(err)),
  })

  return (
    <div>
      <PageHeader
        title="Screen Groups"
        subtitle="Groups control which screens users can see and manage"
        actions={
          <button className="btn-primary" onClick={() => setModal('new')}>
            <Plus size={16} /> New group
          </button>
        }
      />

      {groups.isLoading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {[...Array(3)].map((_, i) => <Skeleton key={i} className="h-32 w-full" />)}
        </div>
      ) : !groups.data?.length ? (
        <Card>
          <EmptyState
            icon={FolderTree}
            title="No screen groups"
            hint="Create groups like 'Ranchi' or 'Patna' to organise screens and restrict user access."
            action={<button className="btn-primary" onClick={() => setModal('new')}><Plus size={16} /> New group</button>}
          />
        </Card>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {groups.data.map((g) => (
            <Card key={g.id} className="p-5 flex flex-col">
              <div className="flex items-start justify-between">
                <div className="rounded-xl bg-primary-500/15 text-primary-400 p-2.5">
                  <FolderTree size={20} />
                </div>
                <div className="flex gap-1">
                  <button className="btn-ghost !p-2" onClick={() => setModal(g)} title="Edit">
                    <Pencil size={14} />
                  </button>
                  <button className="btn-ghost !p-2 text-danger" onClick={() => { setToDelete(g); setDeleteError(null) }} title="Delete">
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>
              <h3 className="font-bold text-txt-primary mt-3">{g.name}</h3>
              <p className="text-sm text-txt-secondary mt-1 flex-1">{g.description || 'No description'}</p>
              <p className="text-xs font-semibold text-txt-secondary mt-3 flex items-center gap-1.5">
                <MonitorPlay size={14} className="text-txt-muted" /> {g.screenCount} screen{g.screenCount === 1 ? '' : 's'}
              </p>
            </Card>
          ))}
        </div>
      )}

      {modal && <GroupModal open onClose={() => setModal(null)} existing={modal === 'new' ? null : modal} />}
      <ConfirmDialog
        open={!!toDelete}
        onClose={() => setToDelete(null)}
        onConfirm={() => deleteMutation.mutate(toDelete.id)}
        busy={deleteMutation.isPending}
        title="Delete group"
        message={`Delete "${toDelete?.name}"? Users restricted to it will lose that scope.`}
      >
        {deleteError && <div className="mt-3 rounded-lg bg-danger-100 text-danger-700 text-sm px-3 py-2">{deleteError}</div>}
      </ConfirmDialog>
    </div>
  )
}
