// User management page (SUPER_ADMIN only): table of portal accounts with
// role badge, screen-group access and active status. Invite/edit share one
// modal; deactivate/activate flips accounts without deleting them, and you
// cannot deactivate yourself.
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, Users as UsersIcon, Pencil } from 'lucide-react'
import { api, errorMessage } from '../api/client'
import { Card, PageHeader, Badge, Skeleton, EmptyState, Modal, Field, Spinner } from '../components/ui'
import { fmtIST } from '../lib/format'
import { useAuth } from '../auth/AuthContext'

const ROLES = [
  { value: 'SUPER_ADMIN', label: 'Super Admin', hint: 'Everything, including user management' },
  { value: 'ADMIN', label: 'Admin', hint: 'Everything in assigned groups, no user management' },
  { value: 'CONTENT_MANAGER', label: 'Content Manager', hint: 'Media, playlists, layouts, schedules' },
  { value: 'VIEWER', label: 'Viewer', hint: 'Read-only dashboards and reports' },
]
const ROLE_TONE = { SUPER_ADMIN: 'primary', ADMIN: 'ink', CONTENT_MANAGER: 'success', VIEWER: 'warning' }

// Invite/edit form. `existing` = edit mode: email is locked, password only
// sent when filled in. Group chips toggle which screen groups the user sees
// (none selected = access to every group).
function UserModal({ open, onClose, existing, groups }) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState({
    email: existing?.email || '',
    fullName: existing?.fullName || '',
    password: '',
    role: existing?.role || 'VIEWER',
    groupIds: new Set((existing?.groups || []).map((g) => g.id)),
  })
  const [error, setError] = useState(null)
  const set = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }))

  const mutation = useMutation({
    mutationFn: (payload) =>
      existing
        ? api.put(`/users/${existing.id}`, payload).then((r) => r.data)
        : api.post('/users', payload).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] })
      onClose()
    },
    onError: (err) => setError(errorMessage(err)),
  })

  const toggleGroup = (id) => {
    setForm((f) => {
      const next = new Set(f.groupIds)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return { ...f, groupIds: next }
    })
  }

  const submit = (e) => {
    e.preventDefault()
    setError(null)
    const groupIds = [...form.groupIds]
    if (existing) {
      mutation.mutate({
        fullName: form.fullName,
        role: form.role,
        password: form.password || null,
        groupIds,
      })
    } else {
      mutation.mutate({
        email: form.email,
        fullName: form.fullName,
        password: form.password,
        role: form.role,
        groupIds,
      })
    }
  }

  return (
    <Modal open={open} onClose={onClose} title={existing ? 'Edit user' : 'Invite user'}>
      <form onSubmit={submit} className="space-y-4">
        <Field label="Email">
          <input className="input" type="email" required disabled={!!existing} value={form.email} onChange={set('email')} />
        </Field>
        <Field label="Full name">
          <input className="input" required value={form.fullName} onChange={set('fullName')} />
        </Field>
        <Field label={existing ? 'New password (leave blank to keep)' : 'Password'} hint="At least 8 characters">
          <input className="input" type="password" required={!existing} minLength={existing && !form.password ? undefined : 8} value={form.password} onChange={set('password')} />
        </Field>
        <Field label="Role">
          <select className="input" value={form.role} onChange={set('role')}>
            {ROLES.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
          </select>
          <p className="text-xs text-txt-muted mt-1">{ROLES.find((r) => r.value === form.role)?.hint}</p>
        </Field>
        <Field label="Screen group access" hint="Leave all unchecked for access to every group">
          <div className="flex flex-wrap gap-2">
            {groups.map((g) => (
              <button
                type="button"
                key={g.id}
                onClick={() => toggleGroup(g.id)}
                className={
                  form.groupIds.has(g.id)
                    ? 'rounded-full bg-grad-primary text-white px-3 py-1 text-xs font-bold'
                    : 'rounded-full bg-hover border border-subtle text-txt-secondary px-3 py-1 text-xs font-semibold hover:bg-white/10'
                }
              >
                {g.name}
              </button>
            ))}
            {groups.length === 0 && <span className="text-xs text-txt-muted">No groups defined yet</span>}
          </div>
        </Field>
        {error && <div className="rounded-btn bg-danger/10 border border-danger/30 text-danger text-sm px-3 py-2">{error}</div>}
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn-primary" disabled={mutation.isPending}>
            {mutation.isPending ? <Spinner className="h-4 w-4" /> : existing ? 'Save changes' : 'Create user'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

export default function UsersPage() {
  const { user: me } = useAuth()
  const queryClient = useQueryClient()
  const [modal, setModal] = useState(null) // null | 'new' | user object

  const users = useQuery({ queryKey: ['users'], queryFn: () => api.get('/users').then((r) => r.data) })
  const groups = useQuery({ queryKey: ['groups'], queryFn: () => api.get('/groups').then((r) => r.data) })

  const activeMutation = useMutation({
    mutationFn: ({ id, active }) => api.post(`/users/${id}/${active ? 'activate' : 'deactivate'}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['users'] }),
  })

  return (
    <div>
      <PageHeader
        title="Users"
        subtitle="Portal accounts, roles and screen-group access"
        actions={
          <button className="btn-primary" onClick={() => setModal('new')}>
            <Plus size={16} /> Invite user
          </button>
        }
      />

      <Card>
        {users.isLoading ? (
          <div className="p-4 space-y-3">
            {[...Array(4)].map((_, i) => <Skeleton key={i} className="h-12 w-full" />)}
          </div>
        ) : !users.data?.length ? (
          <EmptyState icon={UsersIcon} title="No users" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs uppercase tracking-wide text-txt-secondary border-b border-subtle">
                  <th className="px-4 py-3">User</th>
                  <th className="px-4 py-3">Role</th>
                  <th className="px-4 py-3">Group access</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Created</th>
                  <th className="px-4 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-subtle">
                {users.data.map((u) => (
                  <tr key={u.id} className="hover:bg-hover">
                    <td className="px-4 py-3">
                      <p className="font-semibold text-txt-primary">{u.fullName}{u.id === me.id && <span className="text-txt-muted font-normal"> (you)</span>}</p>
                      <p className="text-xs text-txt-secondary">{u.email}</p>
                    </td>
                    <td className="px-4 py-3">
                      <Badge tone={ROLE_TONE[u.role]}>{ROLES.find((r) => r.value === u.role)?.label || u.role}</Badge>
                    </td>
                    <td className="px-4 py-3">
                      {u.groups?.length ? (
                        <div className="flex flex-wrap gap-1">
                          {u.groups.map((g) => <Badge key={g.id}>{g.name}</Badge>)}
                        </div>
                      ) : (
                        <span className="text-xs text-txt-secondary">All groups</span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      {u.active ? <Badge tone="success">Active</Badge> : <Badge tone="danger">Deactivated</Badge>}
                    </td>
                    <td className="px-4 py-3 text-txt-secondary whitespace-nowrap">{fmtIST(u.createdAt, false)}</td>
                    <td className="px-4 py-3">
                      <div className="flex justify-end gap-2">
                        <button className="btn-ghost !px-2.5 !py-1.5" onClick={() => setModal(u)} title="Edit">
                          <Pencil size={14} />
                        </button>
                        {u.id !== me.id && (
                          <button
                            className={u.active ? 'btn-ghost !py-1.5 text-danger' : 'btn-ghost !py-1.5 text-success-700'}
                            onClick={() => activeMutation.mutate({ id: u.id, active: !u.active })}
                          >
                            {u.active ? 'Deactivate' : 'Activate'}
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {modal && (
        <UserModal
          open
          onClose={() => setModal(null)}
          existing={modal === 'new' ? null : modal}
          groups={groups.data || []}
        />
      )}
    </div>
  )
}
