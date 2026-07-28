// Layout designer (/layouts/:id): a visual canvas where zones (MEDIA,
// TICKER, WIDGET, LOGO, WEB) are dragged and resized on a 24-column grid.
// All positions are stored as percentages of the canvas so they scale to any
// screen resolution. The side panel edits the selected zone's settings
// (playlist, ticker text/colors, widget type, logo image, web URL) and its
// stacking order. Changes stay local until "Save layout" PUTs everything.
import { useEffect, useRef, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowLeft, Save, Trash2, ListVideo, Type, Clock3, ImagePlus, Globe,
  ChevronUp, ChevronDown, LayoutPanelTop,
} from 'lucide-react'
import clsx from 'clsx'
import { api, errorMessage } from '../api/client'
import { Skeleton, Spinner, Field, Card } from '../components/ui'

const GRID = 100 / 24 // 24-column grid, in %
// Clamp to 0-100% and snap to the nearest grid line.
const snap = (v) => Math.max(0, Math.min(100, Math.round(v / GRID) * GRID))

const ZONE_META = {
  MEDIA: { label: 'Media', icon: ListVideo, color: 'bg-primary-600/60 text-white' },
  TICKER: { label: 'Ticker', icon: Type, color: 'bg-grad-primary text-white' },
  WIDGET: { label: 'Widget', icon: Clock3, color: 'bg-[#1A1530]/95 text-primary-400' },
  LOGO: { label: 'Logo', icon: ImagePlus, color: 'bg-white/15 text-txt-primary' },
  WEB: { label: 'Web', icon: Globe, color: 'bg-info/25 text-info' },
}

let zoneCounter = 0
const newZoneKey = () => `z-${Date.now()}-${zoneCounter++}`

// One zone rectangle on the canvas. Dragging its body moves it; the little
// corner handle resizes it. Both share startDrag with a different mode.
function ZoneBox({ zone, selected, onSelect, onChange, canvasRef }) {
  const meta = ZONE_META[zone.type]
  const dragRef = useRef(null)

  // Drag/resize math: record the pointer's start position and the zone's
  // original rect, then on every pointermove convert the pixel delta into a
  // percentage of the canvas size, clamp it so the zone stays inside the
  // canvas, and snap the result to the grid. Listeners detach on pointerup.
  const startDrag = (e, mode) => {
    e.preventDefault()
    e.stopPropagation()
    onSelect(zone.key)
    const canvas = canvasRef.current.getBoundingClientRect()
    const startX = e.clientX
    const startY = e.clientY
    const orig = { x: zone.x, y: zone.y, w: zone.w, h: zone.h }

    const onMove = (ev) => {
      const dxPct = ((ev.clientX - startX) / canvas.width) * 100
      const dyPct = ((ev.clientY - startY) / canvas.height) * 100
      if (mode === 'move') {
        // Moving: shift x/y, keeping the whole box on the canvas.
        const x = snap(Math.min(100 - orig.w, Math.max(0, orig.x + dxPct)))
        const y = snap(Math.min(100 - orig.h, Math.max(0, orig.y + dyPct)))
        onChange(zone.key, { x, y })
      } else {
        // Resizing: grow/shrink w/h, at least one grid cell, never past 100%.
        const w = snap(Math.min(100 - orig.x, Math.max(GRID, orig.w + dxPct)))
        const h = snap(Math.min(100 - orig.y, Math.max(GRID, orig.h + dyPct)))
        onChange(zone.key, { w, h })
      }
    }
    const onUp = () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
  }

  return (
    <div
      ref={dragRef}
      onPointerDown={(e) => startDrag(e, 'move')}
      className={clsx(
        'absolute flex flex-col items-center justify-center cursor-move select-none overflow-hidden',
        meta.color,
        selected ? 'ring-4 ring-primary-500 z-20' : 'ring-1 ring-black/30',
      )}
      style={{ left: `${zone.x}%`, top: `${zone.y}%`, width: `${zone.w}%`, height: `${zone.h}%` }}
    >
      <meta.icon size={Math.min(22, 14 + zone.w / 8)} />
      <p className="text-[11px] font-bold mt-1 px-2 text-center leading-tight">
        {meta.label}
        {zone.type === 'MEDIA' && zone.playlistName && <span className="block font-medium opacity-80 truncate max-w-full">{zone.playlistName}</span>}
        {zone.type === 'WIDGET' && <span className="block font-medium opacity-80">{zone.config?.widget || 'CLOCK'}</span>}
      </p>
      <div
        onPointerDown={(e) => startDrag(e, 'resize')}
        className="absolute bottom-0 right-0 h-4 w-4 cursor-se-resize bg-white/80 rounded-tl-md"
        title="Resize"
      />
    </div>
  )
}

// Side panel for the selected zone: numeric x/y/w/h inputs plus fields that
// depend on the zone type; also delete and z-order (forward/back) controls.
function ZoneProperties({ zone, playlists, mediaImages, onChange, onDelete, onZOrder }) {
  if (!zone) {
    return <p className="text-sm text-txt-muted p-1">Select a zone on the canvas to edit it, or add one from the palette above.</p>
  }
  const setConfig = (patch) => onChange(zone.key, { config: { ...zone.config, ...patch } })
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="font-bold text-txt-primary flex items-center gap-2">
          {ZONE_META[zone.type].label} zone
        </p>
        <div className="flex gap-1">
          <button className="btn-ghost !p-1.5" title="Bring forward" onClick={() => onZOrder(zone.key, +1)}><ChevronUp size={14} /></button>
          <button className="btn-ghost !p-1.5" title="Send back" onClick={() => onZOrder(zone.key, -1)}><ChevronDown size={14} /></button>
          <button className="btn-ghost !p-1.5 text-danger" title="Delete zone" onClick={() => onDelete(zone.key)}><Trash2 size={14} /></button>
        </div>
      </div>

      <div className="grid grid-cols-4 gap-2 text-xs">
        {['x', 'y', 'w', 'h'].map((k) => (
          <div key={k}>
            <label className="label">{k}%</label>
            <input
              type="number"
              className="input !px-2 !py-1.5"
              value={Math.round(zone[k] * 10) / 10}
              min={0}
              max={100}
              step={GRID}
              onChange={(e) => onChange(zone.key, { [k]: snap(Number(e.target.value)) })}
            />
          </div>
        ))}
      </div>

      {zone.type === 'MEDIA' && (
        <Field label="Playlist for this zone">
          <select
            className="input"
            value={zone.playlistId || ''}
            onChange={(e) => {
              const p = playlists.find((x) => x.id === e.target.value)
              onChange(zone.key, { playlistId: e.target.value || null, playlistName: p?.name || null })
            }}
          >
            <option value="">— none —</option>
            {playlists.map((p) => (
              <option key={p.id} value={p.id}>{p.name} ({p.itemCount} items)</option>
            ))}
          </select>
        </Field>
      )}

      {zone.type === 'TICKER' && (
        <>
          <Field label="Messages (one per line)">
            <textarea
              className="input"
              rows={4}
              value={(zone.config?.messages || []).join('\n')}
              onChange={(e) => setConfig({ messages: e.target.value.split('\n').filter((x) => x.trim()) })}
              placeholder={'Diwali sale ends Sunday!\nFree home delivery above ₹499'}
            />
          </Field>
          <Field label={`Speed — ${zone.config?.speed || 30}s per loop`}>
            <input type="range" min={8} max={90} value={zone.config?.speed || 30}
              onChange={(e) => setConfig({ speed: Number(e.target.value) })} className="w-full" />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Background">
              <input type="color" className="h-9 w-full rounded-lg border border-subtle" value={zone.config?.bgColor || '#7C3AED'}
                onChange={(e) => setConfig({ bgColor: e.target.value })} />
            </Field>
            <Field label="Text">
              <input type="color" className="h-9 w-full rounded-lg border border-subtle" value={zone.config?.textColor || '#FFFFFF'}
                onChange={(e) => setConfig({ textColor: e.target.value })} />
            </Field>
          </div>
        </>
      )}

      {zone.type === 'WIDGET' && (
        <>
          <Field label="Widget">
            <select className="input" value={zone.config?.widget || 'CLOCK'} onChange={(e) => setConfig({ widget: e.target.value })}>
              <option value="CLOCK">Clock (IST)</option>
              <option value="DATE">Date</option>
              <option value="WEATHER">Weather (placeholder)</option>
            </select>
          </Field>
          {zone.config?.widget === 'WEATHER' && (
            <Field label="City label">
              <input className="input" value={zone.config?.city || ''} onChange={(e) => setConfig({ city: e.target.value })} placeholder="Ranchi" />
            </Field>
          )}
          <div className="grid grid-cols-2 gap-3">
            <Field label="Background">
              <input type="color" className="h-9 w-full rounded-lg border border-subtle" value={zone.config?.bgColor || '#0C0C18'}
                onChange={(e) => setConfig({ bgColor: e.target.value })} />
            </Field>
            <Field label="Text">
              <input type="color" className="h-9 w-full rounded-lg border border-subtle" value={zone.config?.textColor || '#FFFFFF'}
                onChange={(e) => setConfig({ textColor: e.target.value })} />
            </Field>
          </div>
        </>
      )}

      {zone.type === 'LOGO' && (
        <>
          <Field label="Logo image (from Media Library)">
            <select className="input" value={zone.config?.mediaId || ''} onChange={(e) => setConfig({ mediaId: e.target.value || null })}>
              <option value="">ScreenPilot wordmark (default)</option>
              {mediaImages.map((m) => (
                <option key={m.id} value={m.id}>{m.name}</option>
              ))}
            </select>
          </Field>
          <Field label="Corner position">
            <div className="grid grid-cols-4 gap-1.5">
              {[
                ['TL', 0, 0], ['TR', 100 - zone.w, 0], ['BL', 0, 100 - zone.h], ['BR', 100 - zone.w, 100 - zone.h],
              ].map(([label, x, y]) => (
                <button key={label} type="button" className="btn-ghost !py-1.5 text-xs font-bold"
                  onClick={() => onChange(zone.key, { x: snap(x), y: snap(y) })}>
                  {label}
                </button>
              ))}
            </div>
          </Field>
        </>
      )}

      {zone.type === 'WEB' && (
        <Field label="Page URL" hint="Rendered in an iframe on the player">
          <input className="input" type="url" value={zone.config?.url || ''} onChange={(e) => setConfig({ url: e.target.value })}
            placeholder="https://example.com/board" />
        </Field>
      )}
    </div>
  )
}

export default function LayoutDesignerPage() {
  const { id } = useParams()
  const queryClient = useQueryClient()
  const canvasRef = useRef(null)
  const [zones, setZones] = useState(null)
  const [name, setName] = useState('')
  const [selectedKey, setSelectedKey] = useState(null)
  const [dirty, setDirty] = useState(false)
  const [saveError, setSaveError] = useState(null)
  const [savedFlash, setSavedFlash] = useState(false)

  const layout = useQuery({ queryKey: ['layout', id], queryFn: () => api.get(`/layouts/${id}`).then((r) => r.data) })
  const playlists = useQuery({ queryKey: ['playlists'], queryFn: () => api.get('/playlists').then((r) => r.data) })
  const media = useQuery({ queryKey: ['media'], queryFn: () => api.get('/media').then((r) => r.data) })

  useEffect(() => {
    // Copy the fetched layout into local editing state exactly once.
    if (layout.data && zones === null) {
      setName(layout.data.name)
      setZones(
        layout.data.zones.map((z) => ({
          key: z.id,
          type: z.type,
          x: z.x, y: z.y, w: z.w, h: z.h,
          playlistId: z.playlistId,
          playlistName: z.playlistName,
          config: z.config || {},
        })),
      )
    }
  }, [layout.data, zones])

  // All zone edits funnel through here to keep the dirty flag accurate.
  const mutateZones = (fn) => {
    setZones((prev) => fn(prev))
    setDirty(true)
    setSavedFlash(false)
  }

  const changeZone = (key, patch) => mutateZones((prev) => prev.map((z) => (z.key === key ? { ...z, ...patch } : z)))
  const deleteZone = (key) => {
    mutateZones((prev) => prev.filter((z) => z.key !== key))
    setSelectedKey(null)
  }
  // Stacking order = array order (saved as z below); move the zone one slot.
  const zOrder = (key, dir) =>
    mutateZones((prev) => {
      const idx = prev.findIndex((z) => z.key === key)
      const to = idx + dir
      if (to < 0 || to >= prev.length) return prev
      const next = [...prev]
      const [item] = next.splice(idx, 1)
      next.splice(to, 0, item)
      return next
    })

  // Drop a new zone onto the canvas with sensible defaults per type.
  const addZone = (type) => {
    const key = newZoneKey()
    const defaults = {
      MEDIA: { x: snap(25), y: snap(25), w: snap(50), h: snap(50) },
      TICKER: { x: 0, y: snap(87.5), w: 100, h: snap(12.5), config: { messages: ['New ticker message'], speed: 30, bgColor: '#7C3AED', textColor: '#FFFFFF' } },
      WIDGET: { x: snap(75), y: 0, w: snap(25), h: snap(25), config: { widget: 'CLOCK', bgColor: '#0C0C18', textColor: '#FFFFFF' } },
      LOGO: { x: snap(83.33), y: 0, w: snap(16.66), h: snap(16.66), config: {} },
      WEB: { x: snap(25), y: snap(25), w: snap(50), h: snap(50), config: { url: '' } },
    }
    mutateZones((prev) => [...prev, { key, type, config: {}, ...defaults[type] }])
    setSelectedKey(key)
  }

  // Save: PUT the whole layout; array index becomes the zone's z value.
  const saveMutation = useMutation({
    mutationFn: () =>
      api
        .put(`/layouts/${id}`, {
          name,
          orientation: layout.data.orientation,
          zones: zones.map((z, i) => ({
            type: z.type,
            x: z.x, y: z.y, w: z.w, h: z.h, z: i + 1,
            playlistId: z.type === 'MEDIA' ? z.playlistId : null,
            config: z.config && Object.keys(z.config).length ? z.config : null,
          })),
        })
        .then((r) => r.data),
    onSuccess: (data) => {
      queryClient.setQueryData(['layout', id], data)
      queryClient.invalidateQueries({ queryKey: ['layouts'] })
      setDirty(false)
      setSavedFlash(true)
      setSaveError(null)
      setTimeout(() => setSavedFlash(false), 2500)
    },
    onError: (err) => setSaveError(errorMessage(err)),
  })

  if (layout.isLoading || zones === null) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-9 w-64" />
        <Skeleton className="h-[480px] w-full" />
      </div>
    )
  }

  const isPortrait = layout.data.orientation === 'PORTRAIT'
  const selected = zones.find((z) => z.key === selectedKey)
  const mediaImages = (media.data || []).filter((m) => m.type === 'IMAGE')

  return (
    <div>
      <Link to="/layouts" className="flex items-center gap-1.5 text-sm text-txt-muted hover:text-txt-primary mb-3">
        <ArrowLeft size={15} /> Layouts
      </Link>

      <div className="flex flex-wrap items-center gap-3 mb-4">
        <input
          className="text-2xl font-bold text-txt-primary bg-transparent border-b-2 border-transparent hover:border-subtle focus:border-primary-500 focus:outline-none flex-1 min-w-[220px]"
          value={name}
          onChange={(e) => { setName(e.target.value); setDirty(true) }}
        />
        <span className="text-xs font-bold text-txt-muted bg-hover border border-subtle rounded-full px-3 py-1">
          {isPortrait ? 'Portrait 9:16' : 'Landscape 16:9'} · 24-col grid
        </span>
        <button className="btn-primary" disabled={!dirty || saveMutation.isPending} onClick={() => saveMutation.mutate()}>
          {saveMutation.isPending ? <Spinner className="h-4 w-4" /> : <Save size={16} />}
          {savedFlash ? 'Saved ✓' : dirty ? 'Save layout' : 'Saved'}
        </button>
      </div>
      {saveError && <div className="rounded-lg bg-danger/15 text-danger text-sm px-3 py-2 mb-4">{saveError}</div>}

      <div className="flex flex-wrap items-center gap-2 mb-4">
        <span className="text-xs font-bold uppercase tracking-wide text-txt-muted mr-1">Add zone:</span>
        {Object.entries(ZONE_META).map(([type, meta]) => (
          <button key={type} className="btn-ghost !py-1.5" onClick={() => addZone(type)}>
            <meta.icon size={14} className="text-primary-400" /> {meta.label}
          </button>
        ))}
      </div>

      <div className={clsx('grid gap-4', isPortrait ? 'grid-cols-1 lg:grid-cols-[auto_1fr]' : 'grid-cols-1 lg:grid-cols-3')}>
        <div className={isPortrait ? '' : 'lg:col-span-2'}>
          <div
            ref={canvasRef}
            onPointerDown={() => setSelectedKey(null)}
            className={clsx('relative bg-black/70 rounded-xl overflow-hidden mx-auto', isPortrait ? 'max-h-[70vh]' : 'w-full')}
            style={{
              aspectRatio: isPortrait ? '9/16' : '16/9',
              ...(isPortrait ? { height: '70vh' } : {}),
              backgroundImage:
                'linear-gradient(rgba(255,255,255,0.05) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.05) 1px, transparent 1px)',
              backgroundSize: `${100 / 24}% ${100 / 24}%`,
            }}
          >
            {zones.map((z) => (
              <ZoneBox
                key={z.key}
                zone={z}
                selected={z.key === selectedKey}
                onSelect={setSelectedKey}
                onChange={changeZone}
                canvasRef={canvasRef}
              />
            ))}
            {zones.length === 0 && (
              <div className="absolute inset-0 flex flex-col items-center justify-center text-txt-secondary">
                <LayoutPanelTop size={40} />
                <p className="mt-2 text-sm">Add zones from the palette above</p>
              </div>
            )}
          </div>
        </div>

        <Card className="p-4 h-fit">
          <ZoneProperties
            zone={selected}
            playlists={playlists.data || []}
            mediaImages={mediaImages}
            onChange={changeZone}
            onDelete={deleteZone}
            onZOrder={zOrder}
          />
        </Card>
      </div>
    </div>
  )
}
