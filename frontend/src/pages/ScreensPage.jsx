// Screens list page: filterable table of every screen with live status,
// "now playing" and last-seen columns (kept fresh by the portal WebSocket).
// Filters live in the URL query string so links from the Dashboard tree work
// and the state survives refresh. Admins can pair new players (6-char code
// from /player) and bulk-assign selected screens to a group.
import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { MonitorPlay, Plus, Search } from 'lucide-react'
import { api, errorMessage } from '../api/client'
import { Card, PageHeader, StatusDot, Skeleton, EmptyState, Modal, Field, Spinner, Badge } from '../components/ui'
import { offlineFor, timeAgo } from '../lib/format'
import { useAuth, hasRole } from '../auth/AuthContext'

// Pairing form: the admin types the code shown on the TV plus the screen's
// metadata (store, city, group, orientation, coordinates); POST /screens/pair
// claims the code and creates the screen.
function PairScreenModal({ open, onClose, groups }) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState({
    code: '',
    name: '',
    storeName: '',
    city: '',
    state: '',
    groupId: '',
    orientation: 'LANDSCAPE',
    resolution: '1920x1080',
    latitude: '',
    longitude: '',
  })
  const [error, setError] = useState(null)

  const mutation = useMutation({
    mutationFn: (payload) => api.post('/screens/pair', payload).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['screens'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      onClose()
      setForm({ code: '', name: '', storeName: '', city: '', state: '', groupId: '', orientation: 'LANDSCAPE', resolution: '1920x1080', latitude: '', longitude: '' })
      setError(null)
    },
    onError: (err) => setError(errorMessage(err)),
  })

  const set = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }))

  const submit = (e) => {
    e.preventDefault()
    setError(null)
    mutation.mutate({
      code: form.code.trim().toUpperCase(),
      screen: {
        name: form.name,
        storeName: form.storeName,
        city: form.city,
        state: form.state,
        groupId: form.groupId || null,
        orientation: form.orientation,
        resolution: form.resolution,
        latitude: form.latitude === '' ? null : Number(form.latitude),
        longitude: form.longitude === '' ? null : Number(form.longitude),
      },
    })
  }

  return (
    <Modal open={open} onClose={onClose} title="Add screen (pair a player)" wide>
      <p className="text-sm text-ink-400 -mt-2 mb-4">
        Open <code className="bg-ink-50 px-1.5 py-0.5 rounded text-ink-700">/player</code> on the device — it shows a
        6-character pairing code. Enter it below with the screen details.
      </p>
      <form onSubmit={submit} className="space-y-4">
        <div className="flex justify-center">
          <input
            value={form.code}
            onChange={set('code')}
            required
            maxLength={6}
            placeholder="ABC123"
            className="input max-w-[220px] text-center text-2xl font-bold tracking-[0.35em] uppercase"
          />
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <Field label="Screen name">
            <input className="input" required value={form.name} onChange={set('name')} placeholder="Entrance display" />
          </Field>
          <Field label="Store name">
            <input className="input" value={form.storeName} onChange={set('storeName')} placeholder="Lalpur Store" />
          </Field>
          <Field label="City">
            <input className="input" value={form.city} onChange={set('city')} placeholder="Ranchi" />
          </Field>
          <Field label="State">
            <input className="input" value={form.state} onChange={set('state')} list="states" placeholder="Jharkhand" />
            <datalist id="states">
              <option>Jharkhand</option>
              <option>Bihar</option>
              <option>West Bengal</option>
              <option>Chhattisgarh</option>
            </datalist>
          </Field>
          <Field label="Screen group">
            <select className="input" value={form.groupId} onChange={set('groupId')}>
              <option value="">No group</option>
              {groups.map((g) => (
                <option key={g.id} value={g.id}>{g.name}</option>
              ))}
            </select>
          </Field>
          <Field label="Orientation">
            <select className="input" value={form.orientation} onChange={set('orientation')}>
              <option value="LANDSCAPE">Landscape (16:9)</option>
              <option value="PORTRAIT">Portrait (9:16)</option>
            </select>
          </Field>
          <Field label="Resolution">
            <input className="input" value={form.resolution} onChange={set('resolution')} placeholder="1920x1080" />
          </Field>
          <div className="grid grid-cols-2 gap-2">
            <Field label="Latitude">
              <input className="input" type="number" step="any" value={form.latitude} onChange={set('latitude')} placeholder="23.34" />
            </Field>
            <Field label="Longitude">
              <input className="input" type="number" step="any" value={form.longitude} onChange={set('longitude')} placeholder="85.31" />
            </Field>
          </div>
        </div>
        {error && <div className="rounded-lg bg-danger-100 text-danger-700 text-sm px-3 py-2">{error}</div>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn-primary" disabled={mutation.isPending}>
            {mutation.isPending ? <Spinner className="h-4 w-4" /> : 'Pair screen'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

export default function ScreensPage() {
  const { user } = useAuth()
  const [searchParams, setSearchParams] = useSearchParams()
  const [selected, setSelected] = useState(new Set())
  const [pairOpen, setPairOpen] = useState(false)
  const [bulkGroup, setBulkGroup] = useState('')
  const queryClient = useQueryClient()

  // All filters read straight from the URL (single source of truth).
  const filters = {
    search: searchParams.get('search') || '',
    groupId: searchParams.get('groupId') || '',
    state: searchParams.get('state') || '',
    city: searchParams.get('city') || '',
    status: searchParams.get('status') || '',
  }

  const setFilter = (key, value) => {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(key, value)
    else next.delete(key)
    setSearchParams(next, { replace: true })
  }

  const screens = useQuery({ queryKey: ['screens', 'all'], queryFn: () => api.get('/screens').then((r) => r.data) })
  const groups = useQuery({ queryKey: ['groups'], queryFn: () => api.get('/groups').then((r) => r.data) })

  // Apply search + dropdown filters client-side over the full screen list.
  const filtered = useMemo(() => {
    let list = screens.data || []
    if (filters.search) {
      const q = filters.search.toLowerCase()
      list = list.filter(
        (s) =>
          s.name?.toLowerCase().includes(q) ||
          s.storeName?.toLowerCase().includes(q) ||
          s.city?.toLowerCase().includes(q),
      )
    }
    if (filters.groupId) list = list.filter((s) => s.group?.id === filters.groupId)
    if (filters.state) list = list.filter((s) => (s.state || 'Unassigned') === filters.state)
    if (filters.city) list = list.filter((s) => (s.city || 'Unassigned') === filters.city)
    if (filters.status) list = list.filter((s) => s.status === filters.status)
    return list
  }, [screens.data, filters.search, filters.groupId, filters.state, filters.city, filters.status])

  // Dropdown options derived from the data; cities narrow to the picked state.
  const states = useMemo(() => [...new Set((screens.data || []).map((s) => s.state || 'Unassigned'))].sort(), [screens.data])
  const cities = useMemo(
    () =>
      [...new Set(
        (screens.data || [])
          .filter((s) => !filters.state || (s.state || 'Unassigned') === filters.state)
          .map((s) => s.city || 'Unassigned'),
      )].sort(),
    [screens.data, filters.state],
  )

  const bulkMutation = useMutation({
    mutationFn: (payload) => api.post('/screens/bulk-group', payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['screens'] })
      queryClient.invalidateQueries({ queryKey: ['groups'] })
      setSelected(new Set())
      setBulkGroup('')
    },
  })

  // Checkbox selection helpers for the bulk group-assign bar.
  const toggleAll = (checked) => {
    setSelected(checked ? new Set(filtered.map((s) => s.id)) : new Set())
  }
  const toggleOne = (id, checked) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (checked) next.add(id)
      else next.delete(id)
      return next
    })
  }

  const isAdmin = hasRole(user, 'ADMIN')

  return (
    <div>
      <PageHeader
        title="Screens"
        subtitle={`${filtered.length} of ${screens.data?.length ?? 0} screens`}
        actions={
          isAdmin && (
            <button className="btn-primary" onClick={() => setPairOpen(true)}>
              <Plus size={16} /> Add screen
            </button>
          )
        }
      />

      <Card className="p-4 mb-4">
        <div className="flex flex-wrap items-center gap-3">
          <div className="relative flex-1 min-w-[200px]">
            <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-ink-300" />
            <input
              className="input pl-9"
              placeholder="Search name, store, city…"
              value={filters.search}
              onChange={(e) => setFilter('search', e.target.value)}
            />
          </div>
          <select className="input max-w-[170px]" value={filters.groupId} onChange={(e) => setFilter('groupId', e.target.value)}>
            <option value="">All groups</option>
            {(groups.data || []).map((g) => (
              <option key={g.id} value={g.id}>{g.name}</option>
            ))}
          </select>
          <select className="input max-w-[160px]" value={filters.state} onChange={(e) => { setFilter('state', e.target.value); setFilter('city', '') }}>
            <option value="">All states</option>
            {states.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
          <select className="input max-w-[160px]" value={filters.city} onChange={(e) => setFilter('city', e.target.value)}>
            <option value="">All cities</option>
            {cities.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
          <select className="input max-w-[140px]" value={filters.status} onChange={(e) => setFilter('status', e.target.value)}>
            <option value="">Any status</option>
            <option value="ONLINE">Online</option>
            <option value="OFFLINE">Offline</option>
          </select>
        </div>
      </Card>

      {isAdmin && selected.size > 0 && (
        <div className="card bg-ink-800 text-white px-4 py-3 mb-4 flex items-center gap-4">
          <span className="text-sm font-semibold">{selected.size} selected</span>
          <div className="flex items-center gap-2 ml-auto">
            <span className="text-xs text-ink-200">Assign to group:</span>
            <select className="input max-w-[180px] !py-1.5" value={bulkGroup} onChange={(e) => setBulkGroup(e.target.value)}>
              <option value="">— choose —</option>
              {(groups.data || []).map((g) => (
                <option key={g.id} value={g.id}>{g.name}</option>
              ))}
            </select>
            <button
              className="btn-primary !py-1.5"
              disabled={!bulkGroup || bulkMutation.isPending}
              onClick={() => bulkMutation.mutate({ screenIds: [...selected], groupId: bulkGroup })}
            >
              {bulkMutation.isPending ? <Spinner className="h-4 w-4" /> : 'Apply'}
            </button>
          </div>
        </div>
      )}

      <Card>
        {screens.isLoading ? (
          <div className="p-4 space-y-3">
            {[...Array(6)].map((_, i) => <Skeleton key={i} className="h-12 w-full" />)}
          </div>
        ) : filtered.length === 0 ? (
          <EmptyState
            icon={MonitorPlay}
            title="No screens match"
            hint={screens.data?.length ? 'Try clearing some filters.' : 'Pair your first screen: open /player on the device and click "Add screen".'}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs uppercase tracking-wide text-ink-400 border-b border-ink-100">
                  {isAdmin && (
                    <th className="px-4 py-3 w-10">
                      <input
                        type="checkbox"
                        checked={selected.size > 0 && selected.size === filtered.length}
                        onChange={(e) => toggleAll(e.target.checked)}
                      />
                    </th>
                  )}
                  <th className="px-4 py-3">Screen</th>
                  <th className="px-4 py-3">Location</th>
                  <th className="px-4 py-3">Group</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Now playing</th>
                  <th className="px-4 py-3">Last seen</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-ink-100/60">
                {filtered.map((s) => (
                  <tr key={s.id} className="hover:bg-ink-50/60">
                    {isAdmin && (
                      <td className="px-4 py-3">
                        <input
                          type="checkbox"
                          checked={selected.has(s.id)}
                          onChange={(e) => toggleOne(s.id, e.target.checked)}
                        />
                      </td>
                    )}
                    <td className="px-4 py-3">
                      <Link to={`/screens/${s.id}`} className="font-semibold text-ink-800 hover:text-marigold-700">
                        {s.name}
                      </Link>
                      <p className="text-xs text-ink-400">{s.storeName}</p>
                    </td>
                    <td className="px-4 py-3 text-ink-500">
                      {s.city || '—'}{s.state ? `, ${s.state}` : ''}
                    </td>
                    <td className="px-4 py-3">
                      {s.group ? <Badge tone="marigold">{s.group.name}</Badge> : <span className="text-ink-300">—</span>}
                    </td>
                    <td className="px-4 py-3">
                      <span className="flex items-center gap-2">
                        <StatusDot status={s.status} pulse />
                        {s.status === 'ONLINE' ? (
                          <span className="font-semibold text-success-700">Online</span>
                        ) : (
                          <span className="font-semibold text-danger-700">
                            Offline {s.lastHeartbeatAt ? `for ${offlineFor(s)}` : '(never seen)'}
                          </span>
                        )}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-ink-500 max-w-[180px] truncate">
                      {s.status === 'ONLINE' ? (s.currentItemName || 'Idle') : '—'}
                    </td>
                    <td className="px-4 py-3 text-ink-400 whitespace-nowrap">{timeAgo(s.lastHeartbeatAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <PairScreenModal open={pairOpen} onClose={() => setPairOpen(false)} groups={groups.data || []} />
    </div>
  )
}
