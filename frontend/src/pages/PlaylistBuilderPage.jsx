import { useEffect, useMemo, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
  useDroppable,
} from '@dnd-kit/core'
import { SortableContext, useSortable, verticalListSortingStrategy, arrayMove } from '@dnd-kit/sortable'
import { useDraggable } from '@dnd-kit/core'
import { CSS } from '@dnd-kit/utilities'
import {
  ArrowLeft, Search, GripVertical, Trash2, Copy, Plus, Globe, Youtube, Clock,
  Image as ImageIcon, ListVideo, Save,
} from 'lucide-react'
import clsx from 'clsx'
import { api, errorMessage } from '../api/client'
import { Card, Skeleton, EmptyState, Modal, Field, Spinner, Badge } from '../components/ui'
import { fmtSeconds } from '../lib/format'
import { mediaThumbSrc, TYPE_ICON, TYPE_LABEL, effectiveItemDuration } from '../lib/media'

function MiniThumb({ media, className }) {
  const [failed, setFailed] = useState(false)
  const Icon = TYPE_ICON[media?.type] || ImageIcon
  if (!media?.hasThumb || failed) {
    return (
      <div className={clsx('flex items-center justify-center bg-ink-800 text-ink-300 rounded-lg', className)}>
        <Icon size={18} />
      </div>
    )
  }
  return <img src={mediaThumbSrc(media)} alt="" onError={() => setFailed(true)} className={clsx('object-cover rounded-lg bg-ink-800', className)} />
}

function LibraryCard({ media, onAdd }) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `lib:${media.id}`,
    data: { media },
  })
  return (
    <div
      ref={setNodeRef}
      {...attributes}
      {...listeners}
      className={clsx(
        'flex items-center gap-2.5 rounded-lg border border-ink-100 bg-white p-2 cursor-grab active:cursor-grabbing hover:border-marigold/60',
        isDragging && 'opacity-40',
      )}
    >
      <MiniThumb media={media} className="h-10 w-16 shrink-0" />
      <div className="flex-1 min-w-0">
        <p className="text-xs font-semibold text-ink-700 truncate">{media.name}</p>
        <p className="text-[10px] text-ink-400">
          {TYPE_LABEL[media.type]}
          {media.type === 'VIDEO' && media.durationSeconds ? ` · ${fmtSeconds(media.durationSeconds)}` : ''}
        </p>
      </div>
      <button
        className="rounded-lg bg-marigold/15 text-marigold-700 p-1.5 hover:bg-marigold/30"
        title="Add to playlist"
        onClick={(e) => {
          e.stopPropagation()
          onAdd(media)
        }}
        onPointerDown={(e) => e.stopPropagation()}
      >
        <Plus size={14} />
      </button>
    </div>
  )
}

function PlaylistRow({ item, index, onRemove, onDuplicate, onDuration }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: item.key })
  const style = { transform: CSS.Transform.toString(transform), transition }
  const isVideo = item.itemType === 'MEDIA' && item.media?.type === 'VIDEO'
  const external = item.itemType !== 'MEDIA'
  return (
    <div
      ref={setNodeRef}
      style={style}
      className={clsx('flex items-center gap-2 rounded-xl border border-ink-100 bg-white p-2.5', isDragging && 'opacity-40 shadow-lg')}
    >
      <button {...attributes} {...listeners} className="text-ink-200 hover:text-ink-400 cursor-grab active:cursor-grabbing p-1">
        <GripVertical size={16} />
      </button>
      <span className="text-xs font-bold text-ink-300 w-5 text-center">{index + 1}</span>
      {item.itemType === 'MEDIA' ? (
        <MiniThumb media={item.media} className="h-11 w-[72px] shrink-0" />
      ) : (
        <div className="h-11 w-[72px] shrink-0 rounded-lg bg-ink-800 flex items-center justify-center text-marigold">
          {item.itemType === 'YOUTUBE' ? <Youtube size={18} /> : <Globe size={18} />}
        </div>
      )}
      <div className="flex-1 min-w-0">
        <p className="text-sm font-semibold text-ink-800 truncate">
          {item.title || item.media?.name || item.url}
        </p>
        <div className="flex items-center gap-2 mt-0.5">
          <Badge tone={external ? 'warning' : item.media?.type === 'VIDEO' ? 'marigold' : 'ink'} className="!text-[10px] !px-1.5">
            {external ? (item.itemType === 'YOUTUBE' ? 'YouTube' : 'Live URL') : TYPE_LABEL[item.media?.type]}
          </Badge>
          {item.media?.deleted && <Badge tone="danger" className="!text-[10px] !px-1.5">deleted asset</Badge>}
        </div>
      </div>
      <div className="flex items-center gap-1 text-xs text-ink-500">
        <Clock size={13} className="text-ink-300" />
        {isVideo ? (
          <span className="font-semibold w-16 text-right">{fmtSeconds(item.media?.durationSeconds ?? 30)}</span>
        ) : (
          <span className="flex items-center gap-1">
            <input
              type="number"
              min={1}
              max={3600}
              value={item.durationSeconds ?? (external ? 20 : 10)}
              onChange={(e) => onDuration(item.key, Number(e.target.value))}
              className="w-16 rounded-lg border border-ink-200 px-2 py-1 text-right text-xs font-semibold focus:outline-none focus:ring-2 focus:ring-marigold/60"
            />
            s
          </span>
        )}
      </div>
      <button className="p-1.5 text-ink-300 hover:text-ink-600" title="Duplicate" onClick={() => onDuplicate(item.key)}>
        <Copy size={15} />
      </button>
      <button className="p-1.5 text-ink-300 hover:text-danger" title="Remove" onClick={() => onRemove(item.key)}>
        <Trash2 size={15} />
      </button>
    </div>
  )
}

function ExternalModal({ open, onClose, onAdd }) {
  const [type, setType] = useState('URL')
  const [url, setUrl] = useState('')
  const [title, setTitle] = useState('')
  const [duration, setDuration] = useState(20)
  return (
    <Modal open={open} onClose={onClose} title="Add external content">
      <form
        onSubmit={(e) => {
          e.preventDefault()
          onAdd({ itemType: type, url, title, durationSeconds: duration })
          setUrl(''); setTitle(''); setDuration(20)
          onClose()
        }}
        className="space-y-4"
      >
        <Field label="Type">
          <div className="grid grid-cols-2 gap-2">
            <button type="button" onClick={() => setType('URL')}
              className={clsx('rounded-lg border p-3 flex items-center gap-2 text-sm font-semibold', type === 'URL' ? 'border-marigold bg-marigold-50 text-ink-800' : 'border-ink-200 text-ink-500')}>
              <Globe size={16} /> Live URL
            </button>
            <button type="button" onClick={() => setType('YOUTUBE')}
              className={clsx('rounded-lg border p-3 flex items-center gap-2 text-sm font-semibold', type === 'YOUTUBE' ? 'border-marigold bg-marigold-50 text-ink-800' : 'border-ink-200 text-ink-500')}>
              <Youtube size={16} /> YouTube
            </button>
          </div>
        </Field>
        <Field label={type === 'YOUTUBE' ? 'YouTube link' : 'Page URL'}>
          <input className="input" required type="url" value={url} onChange={(e) => setUrl(e.target.value)}
            placeholder={type === 'YOUTUBE' ? 'https://www.youtube.com/watch?v=…' : 'https://example.com/menu'} />
        </Field>
        <Field label="Title (shown in reports)">
          <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Optional" />
        </Field>
        <Field label="Duration on screen (seconds)">
          <input className="input" type="number" min={3} max={3600} value={duration} onChange={(e) => setDuration(Number(e.target.value))} />
        </Field>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn-primary">Add item</button>
        </div>
      </form>
    </Modal>
  )
}

let keyCounter = 0
const newKey = () => `new-${Date.now()}-${keyCounter++}`

export default function PlaylistBuilderPage() {
  const { id } = useParams()
  const queryClient = useQueryClient()
  const [items, setItems] = useState(null) // null until loaded
  const [meta, setMeta] = useState({ name: '', description: '' })
  const [dirty, setDirty] = useState(false)
  const [savedFlash, setSavedFlash] = useState(false)
  const [saveError, setSaveError] = useState(null)
  const [libSearch, setLibSearch] = useState('')
  const [libType, setLibType] = useState('')
  const [externalOpen, setExternalOpen] = useState(false)
  const [activeDrag, setActiveDrag] = useState(null)

  const playlist = useQuery({ queryKey: ['playlist', id], queryFn: () => api.get(`/playlists/${id}`).then((r) => r.data) })
  const media = useQuery({ queryKey: ['media'], queryFn: () => api.get('/media').then((r) => r.data) })

  useEffect(() => {
    if (playlist.data && items === null) {
      setItems(
        playlist.data.items.map((it) => ({
          key: it.id,
          itemType: it.itemType,
          media: it.media,
          url: it.url,
          title: it.title,
          durationSeconds: it.durationSeconds,
        })),
      )
      setMeta({ name: playlist.data.name, description: playlist.data.description || '' })
    }
  }, [playlist.data, items])

  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }))
  const { setNodeRef: setDropRef, isOver } = useDroppable({ id: 'playlist' })

  const totalSeconds = useMemo(
    () => (items || []).filter((i) => !i.media?.deleted).reduce((sum, i) => sum + effectiveItemDuration(i), 0),
    [items],
  )

  const mutate = (fn) => {
    setItems((prev) => fn(prev))
    setDirty(true)
    setSavedFlash(false)
  }

  const addMedia = (m, atIndex = null) =>
    mutate((prev) => {
      const item = { key: newKey(), itemType: 'MEDIA', media: m, url: null, title: m.name, durationSeconds: m.type === 'VIDEO' ? null : 10 }
      const next = [...prev]
      next.splice(atIndex == null ? next.length : atIndex, 0, item)
      return next
    })

  const addExternal = (ext) =>
    mutate((prev) => [...prev, { key: newKey(), media: null, ...ext }])

  const onDragStart = (e) => setActiveDrag(e.active)

  const onDragEnd = (e) => {
    setActiveDrag(null)
    const { active, over } = e
    if (!over) return
    const isLib = String(active.id).startsWith('lib:')
    if (isLib) {
      const m = active.data.current?.media
      if (!m) return
      if (over.id === 'playlist') {
        addMedia(m)
      } else {
        const idx = items.findIndex((i) => i.key === over.id)
        addMedia(m, idx === -1 ? null : idx)
      }
      return
    }
    if (active.id !== over.id && over.id !== 'playlist') {
      mutate((prev) => {
        const oldIndex = prev.findIndex((i) => i.key === active.id)
        const newIndex = prev.findIndex((i) => i.key === over.id)
        if (oldIndex === -1 || newIndex === -1) return prev
        return arrayMove(prev, oldIndex, newIndex)
      })
    }
  }

  const saveMutation = useMutation({
    mutationFn: async () => {
      await api.put(`/playlists/${id}`, { name: meta.name, description: meta.description })
      const payload = {
        items: items.map((i) => ({
          itemType: i.itemType,
          mediaId: i.media?.id || null,
          url: i.url,
          title: i.title,
          durationSeconds: i.durationSeconds,
        })),
      }
      return api.put(`/playlists/${id}/items`, payload).then((r) => r.data)
    },
    onSuccess: (data) => {
      queryClient.setQueryData(['playlist', id], data)
      queryClient.invalidateQueries({ queryKey: ['playlists'] })
      setDirty(false)
      setSavedFlash(true)
      setSaveError(null)
      setTimeout(() => setSavedFlash(false), 2500)
    },
    onError: (err) => setSaveError(errorMessage(err)),
  })

  if (playlist.isLoading || items === null) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-9 w-72" />
        <div className="grid grid-cols-1 lg:grid-cols-5 gap-4">
          <Skeleton className="h-96 lg:col-span-2" />
          <Skeleton className="h-96 lg:col-span-3" />
        </div>
      </div>
    )
  }

  const libraryItems = (media.data || []).filter((m) => {
    if (libSearch && !m.name.toLowerCase().includes(libSearch.toLowerCase())) return false
    if (libType && m.type !== libType) return false
    return true
  })

  return (
    <div>
      <Link to="/playlists" className="flex items-center gap-1.5 text-sm text-ink-400 hover:text-ink-700 mb-3">
        <ArrowLeft size={15} /> Playlists
      </Link>

      <div className="flex flex-wrap items-center gap-3 mb-5">
        <div className="flex-1 min-w-[260px]">
          <input
            className="text-2xl font-bold text-ink-800 bg-transparent border-b-2 border-transparent hover:border-ink-200 focus:border-marigold focus:outline-none w-full"
            value={meta.name}
            onChange={(e) => { setMeta((m) => ({ ...m, name: e.target.value })); setDirty(true) }}
          />
          <input
            className="text-sm text-ink-400 bg-transparent border-b border-transparent hover:border-ink-200 focus:border-marigold focus:outline-none w-full mt-1"
            placeholder="Add a description…"
            value={meta.description}
            onChange={(e) => { setMeta((m) => ({ ...m, description: e.target.value })); setDirty(true) }}
          />
        </div>
        <div className="flex items-center gap-3">
          <div className="rounded-xl bg-ink-800 text-white px-4 py-2 text-sm">
            <span className="text-ink-200 text-xs block leading-none mb-0.5">Total loop</span>
            <span className="font-bold text-marigold">{fmtSeconds(totalSeconds)}</span>
            <span className="text-ink-300"> · {items.length} items</span>
          </div>
          <button className="btn-primary" disabled={!dirty || saveMutation.isPending} onClick={() => saveMutation.mutate()}>
            {saveMutation.isPending ? <Spinner className="h-4 w-4" /> : <Save size={16} />}
            {savedFlash ? 'Saved ✓' : dirty ? 'Save changes' : 'Saved'}
          </button>
        </div>
      </div>
      {saveError && <div className="rounded-lg bg-danger-100 text-danger-700 text-sm px-3 py-2 mb-4">{saveError}</div>}

      <DndContext sensors={sensors} onDragStart={onDragStart} onDragEnd={onDragEnd}>
        <div className="grid grid-cols-1 lg:grid-cols-5 gap-4 items-start">
          <Card className="lg:col-span-2 p-4">
            <h2 className="font-bold text-ink-800 mb-3">Media library</h2>
            <div className="flex gap-2 mb-3">
              <div className="relative flex-1">
                <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-ink-300" />
                <input className="input pl-8 !py-1.5 text-sm" placeholder="Search…" value={libSearch} onChange={(e) => setLibSearch(e.target.value)} />
              </div>
              <select className="input max-w-[110px] !py-1.5 text-sm" value={libType} onChange={(e) => setLibType(e.target.value)}>
                <option value="">All</option>
                <option value="VIDEO">Videos</option>
                <option value="IMAGE">Images</option>
                <option value="PDF">PDFs</option>
              </select>
            </div>
            <button className="btn-ghost w-full mb-3 !justify-start" onClick={() => setExternalOpen(true)}>
              <Globe size={15} className="text-marigold-600" /> Add live URL / YouTube…
            </button>
            {media.isLoading ? (
              <div className="space-y-2">{[...Array(5)].map((_, i) => <Skeleton key={i} className="h-14 w-full" />)}</div>
            ) : libraryItems.length === 0 ? (
              <EmptyState icon={ImageIcon} title="No media found" hint="Upload files in the Media Library first." />
            ) : (
              <div className="space-y-2 max-h-[520px] overflow-y-auto pr-1">
                {libraryItems.map((m) => (
                  <LibraryCard key={m.id} media={m} onAdd={addMedia} />
                ))}
              </div>
            )}
          </Card>

          <Card className={clsx('lg:col-span-3 p-4 transition-colors', isOver && 'ring-2 ring-marigold bg-marigold-50/40')}>
            <h2 className="font-bold text-ink-800 mb-3">Playlist loop <span className="text-ink-300 font-normal text-sm">— plays top to bottom, then repeats</span></h2>
            <div ref={setDropRef} className="min-h-[420px]">
              {items.length === 0 ? (
                <EmptyState
                  icon={ListVideo}
                  title="Drag media here"
                  hint="Drag assets from the library panel, or click the + button on any asset."
                />
              ) : (
                <SortableContext items={items.map((i) => i.key)} strategy={verticalListSortingStrategy}>
                  <div className="space-y-2">
                    {items.map((item, index) => (
                      <PlaylistRow
                        key={item.key}
                        item={item}
                        index={index}
                        onRemove={(key) => mutate((prev) => prev.filter((i) => i.key !== key))}
                        onDuplicate={(key) =>
                          mutate((prev) => {
                            const idx = prev.findIndex((i) => i.key === key)
                            const clone = { ...prev[idx], key: newKey() }
                            const next = [...prev]
                            next.splice(idx + 1, 0, clone)
                            return next
                          })
                        }
                        onDuration={(key, val) =>
                          mutate((prev) => prev.map((i) => (i.key === key ? { ...i, durationSeconds: val } : i)))
                        }
                      />
                    ))}
                  </div>
                </SortableContext>
              )}
            </div>
          </Card>
        </div>

        <DragOverlay>
          {activeDrag && String(activeDrag.id).startsWith('lib:') && activeDrag.data.current?.media && (
            <div className="flex items-center gap-2.5 rounded-lg border-2 border-marigold bg-white p-2 shadow-xl w-64">
              <MiniThumb media={activeDrag.data.current.media} className="h-10 w-16 shrink-0" />
              <p className="text-xs font-semibold text-ink-700 truncate">{activeDrag.data.current.media.name}</p>
            </div>
          )}
        </DragOverlay>
      </DndContext>

      <ExternalModal open={externalOpen} onClose={() => setExternalOpen(false)} onAdd={addExternal} />
    </div>
  )
}
