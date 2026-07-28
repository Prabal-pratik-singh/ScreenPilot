// Three-step schedule wizard (create at /schedules/new, edit at
// /schedules/:id/edit): 1) pick a playlist or layout, 2) tick target screens
// in a state > city > store tree, 3) set the IST timing (all-day or window,
// days of week, optional date range). Publishing first asks the server for
// overlapping schedules; any conflicts appear in a dialog where the user can
// cancel or override (deactivate) them before saving.
import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams, Link } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import {
  ArrowLeft, ArrowRight, Check, ListVideo, LayoutPanelTop, ChevronDown, ChevronRight,
  AlertTriangle, CalendarClock, Clock,
} from 'lucide-react'
import clsx from 'clsx'
import { api, errorMessage } from '../api/client'
import { Card, Skeleton, EmptyState, Modal, Spinner, StatusDot, Badge } from '../components/ui'
import { fmtSeconds } from '../lib/format'

const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']
const DAY_LABEL = { MON: 'Mon', TUE: 'Tue', WED: 'Wed', THU: 'Thu', FRI: 'Fri', SAT: 'Sat', SUN: 'Sun' }

// ---------- step 2: screen tree ----------

// Group the flat screen list into nested Maps: state -> city -> store -> [screens].
function buildTree(screens) {
  const tree = new Map()
  for (const s of screens) {
    const state = s.state || 'Unassigned'
    const city = s.city || 'Unassigned'
    const store = s.storeName || 'Unassigned'
    if (!tree.has(state)) tree.set(state, new Map())
    if (!tree.get(state).has(city)) tree.get(state).set(city, new Map())
    if (!tree.get(state).get(city).has(store)) tree.get(state).get(city).set(store, [])
    tree.get(state).get(city).get(store).push(s)
  }
  return tree
}

// One expandable tree level with a tri-state checkbox: checked when every
// screen underneath is selected, indeterminate when only some are.
function TreeCheckbox({ label, screens, depth, selected, onToggle, children, defaultOpen = false }) {
  const [open, setOpen] = useState(defaultOpen)
  const ids = screens.map((s) => s.id)
  const selCount = ids.filter((id) => selected.has(id)).length
  const all = selCount === ids.length && ids.length > 0
  const some = selCount > 0 && !all
  const online = screens.filter((s) => s.status === 'ONLINE').length
  return (
    <div>
      <div className="flex items-center gap-2 py-1.5 px-2 rounded-lg hover:bg-ink-50" style={{ marginLeft: depth * 20 }}>
        {children ? (
          <button type="button" className="text-ink-300" onClick={() => setOpen(!open)}>
            {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          </button>
        ) : (
          <span className="w-[14px]" />
        )}
        <input
          type="checkbox"
          checked={all}
          ref={(el) => el && (el.indeterminate = some)}
          onChange={() => onToggle(ids, !all)}
        />
        <span className="text-sm font-semibold text-ink-700 flex-1 cursor-pointer" onClick={() => onToggle(ids, !all)}>
          {label}
        </span>
        <span className="text-[11px] text-ink-400">{selCount}/{ids.length} selected</span>
        <span className="flex items-center gap-1 text-[11px] text-success-700 font-semibold">
          <StatusDot status="ONLINE" className="h-1.5 w-1.5" />{online}
        </span>
      </div>
      {open && children}
    </div>
  )
}

// Renders the nested tree with individual screen rows at the deepest level.
function ScreenTree({ screens, selected, onToggle }) {
  const tree = useMemo(() => buildTree(screens), [screens])
  return (
    <div className="max-h-[420px] overflow-y-auto pr-2">
      {[...tree.entries()].map(([state, cities]) => {
        const stateScreens = [...cities.values()].flatMap((m) => [...m.values()].flat())
        return (
          <TreeCheckbox key={state} label={state} screens={stateScreens} depth={0} selected={selected} onToggle={onToggle} defaultOpen>
            {[...cities.entries()].map(([city, stores]) => {
              const cityScreens = [...stores.values()].flat()
              return (
                <TreeCheckbox key={city} label={city} screens={cityScreens} depth={1} selected={selected} onToggle={onToggle} defaultOpen>
                  {[...stores.entries()].map(([store, storeScreens]) => (
                    <TreeCheckbox key={store} label={store} screens={storeScreens} depth={2} selected={selected} onToggle={onToggle}>
                      {storeScreens.map((s) => (
                        <div key={s.id} className="flex items-center gap-2 py-1 px-2 rounded-lg hover:bg-ink-50" style={{ marginLeft: 80 }}>
                          <span className="w-[14px]" />
                          <input type="checkbox" checked={selected.has(s.id)} onChange={() => onToggle([s.id], !selected.has(s.id))} />
                          <StatusDot status={s.status} className="h-2 w-2" />
                          <span className="text-sm text-ink-600 cursor-pointer" onClick={() => onToggle([s.id], !selected.has(s.id))}>
                            {s.name}
                          </span>
                        </div>
                      ))}
                    </TreeCheckbox>
                  ))}
                </TreeCheckbox>
              )
            })}
          </TreeCheckbox>
        )
      })}
    </div>
  )
}

// ---------- plain language summary ----------

// Turns the form into a readable sentence for the review box, e.g.
// "Plays 10:00-22:00 IST, Mon-Fri, from 2026-08-01, on 12 screens."
function summarize(form, screenCount) {
  const time = form.allDay ? 'all day' : `${form.startTime}–${form.endTime} IST`
  const days =
    form.days.length === 0 || form.days.length === 7
      ? ''
      : `, ${form.days.map((d) => DAY_LABEL[d]).join('–').length <= 12 && contiguous(form.days)
          ? `${DAY_LABEL[form.days[0]]}–${DAY_LABEL[form.days[form.days.length - 1]]}`
          : form.days.map((d) => DAY_LABEL[d]).join(', ')}`
  let dates = ''
  if (form.dateFrom && form.dateTo) dates = `, ${form.dateFrom} to ${form.dateTo}`
  else if (form.dateFrom) dates = `, from ${form.dateFrom}`
  else if (form.dateTo) dates = `, until ${form.dateTo}`
  return `Plays ${time}${days}${dates}, on ${screenCount} screen${screenCount === 1 ? '' : 's'}.`
}

// True when the picked days form an unbroken run (so "Mon-Fri" reads nicely).
function contiguous(days) {
  const idx = days.map((d) => DAYS.indexOf(d)).sort((a, b) => a - b)
  for (let i = 1; i < idx.length; i++) if (idx[i] !== idx[i - 1] + 1) return false
  return idx.length > 2
}

// ---------- wizard ----------

export default function ScheduleWizardPage() {
  const { id } = useParams() // present in edit mode
  const navigate = useNavigate()
  const [step, setStep] = useState(0)
  const [form, setForm] = useState({
    name: '',
    contentType: 'PLAYLIST',
    playlistId: null,
    layoutId: null,
    allDay: true,
    startTime: '10:00',
    endTime: '22:00',
    days: [],
    dateFrom: '',
    dateTo: '',
  })
  const [selected, setSelected] = useState(new Set())
  const [conflicts, setConflicts] = useState(null) // null | [] | [conflicts]
  const [publishError, setPublishError] = useState(null)
  const [loadedForEdit, setLoadedForEdit] = useState(false)

  const playlists = useQuery({ queryKey: ['playlists'], queryFn: () => api.get('/playlists').then((r) => r.data) })
  const layouts = useQuery({
    queryKey: ['layouts'],
    queryFn: () => api.get('/layouts').then((r) => r.data).catch(() => []),
    retry: false,
  })
  const screens = useQuery({ queryKey: ['screens', 'all'], queryFn: () => api.get('/screens').then((r) => r.data) })
  const existing = useQuery({
    queryKey: ['schedule', id],
    queryFn: () => api.get(`/schedules/${id}`).then((r) => r.data),
    enabled: !!id,
  })

  useEffect(() => {
    // Edit mode: hydrate the form from the fetched schedule exactly once.
    if (id && existing.data && !loadedForEdit) {
      const s = existing.data
      setForm({
        name: s.name,
        contentType: s.contentType,
        playlistId: s.playlistId,
        layoutId: s.layoutId,
        allDay: s.allDay,
        startTime: s.startTime ? String(s.startTime).slice(0, 5) : '10:00',
        endTime: s.endTime ? String(s.endTime).slice(0, 5) : '22:00',
        days: s.daysOfWeek || [],
        dateFrom: s.dateFrom || '',
        dateTo: s.dateTo || '',
      })
      setSelected(new Set(s.screens.map((x) => x.id)))
      setLoadedForEdit(true)
    }
  }, [id, existing.data, loadedForEdit])

  const toggleScreens = (ids, on) => {
    setSelected((prev) => {
      const next = new Set(prev)
      ids.forEach((sid) => (on ? next.add(sid) : next.delete(sid)))
      return next
    })
  }

  // Assemble the API payload; a blank name is auto-filled from the chosen
  // content, and overrideIds lists conflicting schedules to deactivate.
  const buildPayload = (overrideIds = null) => ({
    name:
      form.name.trim() ||
      `${form.contentType === 'PLAYLIST'
        ? playlists.data?.find((p) => p.id === form.playlistId)?.name
        : layouts.data?.find((l) => l.id === form.layoutId)?.name || 'Layout'} schedule`,
    contentType: form.contentType,
    playlistId: form.playlistId,
    layoutId: form.layoutId,
    screenIds: [...selected],
    allDay: form.allDay,
    startTime: form.allDay ? null : form.startTime,
    endTime: form.allDay ? null : form.endTime,
    daysOfWeek: form.days.length === 0 || form.days.length === 7 ? null : form.days,
    dateFrom: form.dateFrom || null,
    dateTo: form.dateTo || null,
    overrideScheduleIds: overrideIds,
  })

  const publishMutation = useMutation({
    mutationFn: async (overrideIds) => {
      const payload = buildPayload(overrideIds)
      if (id) return api.put(`/schedules/${id}`, payload).then((r) => r.data)
      return api.post('/schedules', payload).then((r) => r.data)
    },
    onSuccess: () => navigate('/schedules'),
    onError: (err) => setPublishError(errorMessage(err)),
  })

  // Conflict flow: 1) dry-run the payload against preview-conflicts (edit
  // mode excludes this schedule itself); 2) no overlaps -> publish straight
  // away; 3) overlaps -> open the dialog, and "Override & publish" republishes
  // with the conflicting schedule ids marked for deactivation.
  const checkAndPublish = async () => {
    setPublishError(null)
    try {
      const res = await api.post(`/schedules/preview-conflicts${id ? `?excludeId=${id}` : ''}`, buildPayload())
      if (res.data.conflicts.length > 0) {
        setConflicts(res.data.conflicts)
      } else {
        publishMutation.mutate(null)
      }
    } catch (err) {
      setPublishError(errorMessage(err))
    }
  }

  const contentChosen = form.contentType === 'PLAYLIST' ? !!form.playlistId : !!form.layoutId
  const canNext = step === 0 ? contentChosen : step === 1 ? selected.size > 0 : true
  const timingValid = form.allDay || (form.startTime && form.endTime && form.startTime !== form.endTime)

  const steps = ['Pick content', 'Pick screens', 'Pick timing']

  return (
    <div className="max-w-4xl mx-auto">
      <Link to="/schedules" className="flex items-center gap-1.5 text-sm text-ink-400 hover:text-ink-700 mb-3">
        <ArrowLeft size={15} /> Schedules
      </Link>
      <h1 className="text-2xl font-bold text-ink-800 mb-1">{id ? 'Edit schedule' : 'New schedule'}</h1>
      <p className="text-sm text-ink-400 mb-6">Publish content to screens — pushed live over WebSocket.</p>

      <div className="flex items-center gap-2 mb-6">
        {steps.map((label, i) => (
          <div key={label} className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => i < step && setStep(i)}
              className={clsx(
                'flex items-center gap-2 rounded-full px-4 py-1.5 text-sm font-bold',
                i === step ? 'bg-ink-800 text-white' : i < step ? 'bg-success-100 text-success-700' : 'bg-ink-50 text-ink-400',
              )}
            >
              {i < step ? <Check size={14} /> : <span>{i + 1}</span>} {label}
            </button>
            {i < steps.length - 1 && <div className="w-6 h-px bg-ink-200" />}
          </div>
        ))}
      </div>

      {/* ------- step 1: content ------- */}
      {step === 0 && (
        <Card className="p-6">
          <div className="grid grid-cols-2 gap-3 mb-5 max-w-md">
            {[
              { key: 'PLAYLIST', label: 'Playlist', icon: ListVideo, hint: 'A fullscreen loop' },
              { key: 'LAYOUT', label: 'Layout', icon: LayoutPanelTop, hint: 'Multi-zone screen' },
            ].map(({ key, label, icon: Icon, hint }) => (
              <button
                key={key}
                type="button"
                onClick={() => setForm((f) => ({ ...f, contentType: key }))}
                className={clsx(
                  'rounded-xl border-2 p-4 text-left',
                  form.contentType === key ? 'border-marigold bg-marigold-50' : 'border-ink-100 hover:border-ink-200',
                )}
              >
                <Icon size={20} className={form.contentType === key ? 'text-marigold-700' : 'text-ink-400'} />
                <p className="font-bold text-ink-800 mt-2">{label}</p>
                <p className="text-xs text-ink-400">{hint}</p>
              </button>
            ))}
          </div>

          {form.contentType === 'PLAYLIST' ? (
            playlists.isLoading ? (
              <div className="space-y-2">{[...Array(3)].map((_, i) => <Skeleton key={i} className="h-14" />)}</div>
            ) : playlists.data?.length ? (
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                {playlists.data.map((p) => (
                  <button
                    key={p.id}
                    type="button"
                    onClick={() => setForm((f) => ({ ...f, playlistId: p.id }))}
                    className={clsx(
                      'rounded-xl border-2 p-3.5 text-left flex items-center gap-3',
                      form.playlistId === p.id ? 'border-marigold bg-marigold-50' : 'border-ink-100 hover:border-ink-200',
                    )}
                  >
                    <div className="rounded-lg bg-ink-800 text-marigold p-2"><ListVideo size={16} /></div>
                    <div className="flex-1 min-w-0">
                      <p className="font-semibold text-ink-800 truncate">{p.name}</p>
                      <p className="text-xs text-ink-400 flex items-center gap-1">
                        {p.itemCount} items · <Clock size={11} /> {fmtSeconds(p.totalDurationSeconds)} loop
                      </p>
                    </div>
                    {form.playlistId === p.id && <Check size={17} className="text-marigold-700" />}
                  </button>
                ))}
              </div>
            ) : (
              <EmptyState icon={ListVideo} title="No playlists yet" hint="Create one under Playlists first." />
            )
          ) : layouts.data?.length ? (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {layouts.data.map((l) => (
                <button
                  key={l.id}
                  type="button"
                  onClick={() => setForm((f) => ({ ...f, layoutId: l.id }))}
                  className={clsx(
                    'rounded-xl border-2 p-3.5 text-left flex items-center gap-3',
                    form.layoutId === l.id ? 'border-marigold bg-marigold-50' : 'border-ink-100 hover:border-ink-200',
                  )}
                >
                  <div className="rounded-lg bg-marigold-100 text-marigold-800 p-2"><LayoutPanelTop size={16} /></div>
                  <div className="flex-1 min-w-0">
                    <p className="font-semibold text-ink-800 truncate">{l.name}</p>
                    <p className="text-xs text-ink-400">{l.zoneCount ?? l.zones?.length ?? 0} zones · {l.orientation === 'PORTRAIT' ? '9:16' : '16:9'}</p>
                  </div>
                  {form.layoutId === l.id && <Check size={17} className="text-marigold-700" />}
                </button>
              ))}
            </div>
          ) : (
            <EmptyState icon={LayoutPanelTop} title="No layouts yet" hint="Design one under Layouts first." />
          )}
        </Card>
      )}

      {/* ------- step 2: screens ------- */}
      {step === 1 && (
        <Card className="p-6">
          <div className="flex items-center justify-between mb-3">
            <p className="text-sm text-ink-500">Select screens by state, city or store — or tick them individually.</p>
            <Badge tone="marigold">{selected.size} selected</Badge>
          </div>
          {screens.isLoading ? (
            <div className="space-y-2">{[...Array(5)].map((_, i) => <Skeleton key={i} className="h-9" />)}</div>
          ) : screens.data?.length ? (
            <ScreenTree screens={screens.data} selected={selected} onToggle={toggleScreens} />
          ) : (
            <EmptyState icon={CalendarClock} title="No screens available" hint="Pair screens first." />
          )}
        </Card>
      )}

      {/* ------- step 3: timing ------- */}
      {step === 2 && (
        <Card className="p-6 space-y-6">
          <div>
            <label className="label">Schedule name</label>
            <input
              className="input max-w-md"
              placeholder="Auto-named from content if left blank"
              value={form.name}
              onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
            />
          </div>

          <div className="grid grid-cols-2 gap-3 max-w-md">
            <button
              type="button"
              onClick={() => setForm((f) => ({ ...f, allDay: true }))}
              className={clsx('rounded-xl border-2 p-4 text-left', form.allDay ? 'border-marigold bg-marigold-50' : 'border-ink-100')}
            >
              <p className="font-bold text-ink-800">All day</p>
              <p className="text-xs text-ink-400">Base loop, 24 hours</p>
            </button>
            <button
              type="button"
              onClick={() => setForm((f) => ({ ...f, allDay: false }))}
              className={clsx('rounded-xl border-2 p-4 text-left', !form.allDay ? 'border-marigold bg-marigold-50' : 'border-ink-100')}
            >
              <p className="font-bold text-ink-800">Time window</p>
              <p className="text-xs text-ink-400">Beats all-day during its hours</p>
            </button>
          </div>

          {!form.allDay && (
            <div className="flex items-center gap-3">
              <div>
                <label className="label">Start (IST)</label>
                <input type="time" className="input" value={form.startTime} onChange={(e) => setForm((f) => ({ ...f, startTime: e.target.value }))} />
              </div>
              <span className="text-ink-300 mt-5">→</span>
              <div>
                <label className="label">End (IST)</label>
                <input type="time" className="input" value={form.endTime} onChange={(e) => setForm((f) => ({ ...f, endTime: e.target.value }))} />
              </div>
            </div>
          )}

          <div>
            <label className="label">Days of week <span className="text-ink-300 normal-case">(none = every day)</span></label>
            <div className="flex gap-1.5">
              {DAYS.map((d) => (
                <button
                  key={d}
                  type="button"
                  onClick={() =>
                    setForm((f) => ({ ...f, days: f.days.includes(d) ? f.days.filter((x) => x !== d) : [...f.days, d] }))
                  }
                  className={clsx(
                    'w-12 rounded-lg py-2 text-xs font-bold',
                    form.days.includes(d) ? 'bg-ink-800 text-marigold' : 'bg-ink-50 text-ink-400 hover:bg-ink-100',
                  )}
                >
                  {DAY_LABEL[d]}
                </button>
              ))}
            </div>
          </div>

          <div className="flex items-center gap-3">
            <div>
              <label className="label">From date <span className="text-ink-300 normal-case">(optional)</span></label>
              <input type="date" className="input" value={form.dateFrom} onChange={(e) => setForm((f) => ({ ...f, dateFrom: e.target.value }))} />
            </div>
            <div>
              <label className="label">To date <span className="text-ink-300 normal-case">(optional)</span></label>
              <input type="date" className="input" value={form.dateTo} onChange={(e) => setForm((f) => ({ ...f, dateTo: e.target.value }))} />
            </div>
          </div>

          <div className="rounded-xl bg-ink-800 text-white p-4 flex items-start gap-3">
            <CalendarClock size={18} className="text-marigold mt-0.5" />
            <p className="text-sm">{summarize(form, selected.size)}</p>
          </div>
          {publishError && <div className="rounded-lg bg-danger-100 text-danger-700 text-sm px-3 py-2">{publishError}</div>}
        </Card>
      )}

      <div className="flex justify-between mt-6">
        <button className="btn-ghost" onClick={() => (step === 0 ? navigate('/schedules') : setStep(step - 1))}>
          <ArrowLeft size={15} /> {step === 0 ? 'Cancel' : 'Back'}
        </button>
        {step < 2 ? (
          <button className="btn-primary" disabled={!canNext} onClick={() => setStep(step + 1)}>
            Next <ArrowRight size={15} />
          </button>
        ) : (
          <button className="btn-primary" disabled={!timingValid || publishMutation.isPending} onClick={checkAndPublish}>
            {publishMutation.isPending ? <Spinner className="h-4 w-4" /> : <Check size={15} />}
            {id ? 'Save & publish' : 'Publish'}
          </button>
        )}
      </div>

      {/* ------- conflict dialog ------- */}
      <Modal open={!!conflicts} onClose={() => setConflicts(null)} title="Overlapping schedules found" wide>
        <div className="flex items-start gap-3 rounded-xl bg-warning-100 text-warning-700 p-3.5 mb-4">
          <AlertTriangle size={18} className="mt-0.5 shrink-0" />
          <p className="text-sm">
            These schedules overlap with the one you're publishing, on the same screens and in the same time window.
            <span className="font-bold"> Override</span> deactivates them; <span className="font-bold">Cancel</span> leaves everything unchanged.
          </p>
        </div>
        <div className="space-y-3 max-h-72 overflow-y-auto">
          {(conflicts || []).map((c) => (
            <div key={c.scheduleId} className="rounded-xl border border-ink-100 p-3.5">
              <p className="font-bold text-ink-800">{c.scheduleName}</p>
              <p className="text-xs text-ink-400">{c.window}</p>
              <p className="text-xs text-ink-500 mt-1.5">
                Shared screens: {c.screens.map((s) => s.name).join(', ')}
              </p>
            </div>
          ))}
        </div>
        <div className="flex justify-end gap-2 mt-5">
          <button className="btn-ghost" onClick={() => setConflicts(null)}>Cancel</button>
          <button
            className="btn-primary"
            disabled={publishMutation.isPending}
            onClick={() => publishMutation.mutate(conflicts.map((c) => c.scheduleId))}
          >
            {publishMutation.isPending ? <Spinner className="h-4 w-4" /> : 'Override & publish'}
          </button>
        </div>
      </Modal>
    </div>
  )
}
