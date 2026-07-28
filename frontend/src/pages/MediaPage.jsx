// Media Library page: grid of uploaded videos/images/PDFs with search,
// type/tag/folder filters and hover actions (preview, edit, delete).
// Uploading works two ways — the Upload button or dragging files anywhere
// onto the page — with per-file progress bars driven by axios
// onUploadProgress. Deleting warns which playlists still use the asset.
import { useCallback, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { UploadCloud, Search, Image as ImageIcon, Play, Pencil, Trash2, Eye, FolderOpen, Tag } from 'lucide-react'
import clsx from 'clsx'
import { api, errorMessage } from '../api/client'
import { Card, PageHeader, Skeleton, EmptyState, Modal, Field, Spinner, Badge } from '../components/ui'
import { fmtBytes, fmtSeconds, timeAgo } from '../lib/format'
import { mediaThumbSrc, mediaFileSrc, TYPE_ICON, TYPE_LABEL } from '../lib/media'
import { useAuth, hasRole } from '../auth/AuthContext'

// Thumbnail with graceful fallback: shows the signed thumb image, or a
// type icon when there is no thumb / it fails to load.
function Thumb({ asset, className }) {
  const [failed, setFailed] = useState(false)
  const Icon = TYPE_ICON[asset.type] || ImageIcon
  if (!asset.hasThumb || failed) {
    return (
      <div className={clsx('flex items-center justify-center bg-card-inner text-txt-muted', className)}>
        <Icon size={32} />
      </div>
    )
  }
  return (
    <img
      src={mediaThumbSrc(asset)}
      alt={asset.name}
      loading="lazy"
      onError={() => setFailed(true)}
      className={clsx('object-cover bg-card-inner', className)}
    />
  )
}

// Full preview in a modal: <video> for videos, <img> for images, <iframe>
// for PDFs, plus size/dimensions/tags metadata underneath.
function PreviewModal({ asset, onClose }) {
  return (
    <Modal open={!!asset} onClose={onClose} title={asset?.name || ''} wide>
      {asset && (
        <div>
          <div className="rounded-xl overflow-hidden bg-card-inner flex items-center justify-center min-h-[300px]">
            {asset.type === 'VIDEO' && (
              <video src={mediaFileSrc(asset)} controls autoPlay className="max-h-[60vh] w-full" />
            )}
            {asset.type === 'IMAGE' && (
              <img src={mediaFileSrc(asset)} alt={asset.name} className="max-h-[60vh] object-contain" />
            )}
            {asset.type === 'PDF' && (
              <iframe title={asset.name} src={mediaFileSrc(asset)} className="w-full h-[60vh] bg-white" />
            )}
          </div>
          <div className="flex flex-wrap gap-x-6 gap-y-1 mt-4 text-sm text-txt-secondary">
            <span>{TYPE_LABEL[asset.type]}</span>
            <span>{fmtBytes(asset.sizeBytes)}</span>
            {asset.width && <span>{asset.width}×{asset.height}</span>}
            {asset.durationSeconds && <span>{fmtSeconds(asset.durationSeconds)}</span>}
            {asset.folder && <span className="flex items-center gap-1"><FolderOpen size={14} /> {asset.folder}</span>}
            {asset.uploadedBy && <span>by {asset.uploadedBy.name}</span>}
          </div>
          {asset.tags?.length > 0 && (
            <div className="flex flex-wrap gap-1.5 mt-2">
              {asset.tags.map((t) => <Badge key={t} tone="primary">{t}</Badge>)}
            </div>
          )}
        </div>
      )}
    </Modal>
  )
}

// Rename / move to folder / retag one asset.
function EditModal({ asset, folders, onClose }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(asset.name)
  const [folder, setFolder] = useState(asset.folder || '')
  const [tags, setTags] = useState((asset.tags || []).join(', '))
  const [error, setError] = useState(null)

  const mutation = useMutation({
    mutationFn: () =>
      api.put(`/media/${asset.id}`, {
        name,
        folder: folder || null,
        tags: tags.split(',').map((t) => t.trim()).filter(Boolean),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['media'] })
      onClose()
    },
    onError: (err) => setError(errorMessage(err)),
  })

  return (
    <Modal open onClose={onClose} title="Edit media">
      <form onSubmit={(e) => { e.preventDefault(); mutation.mutate() }} className="space-y-4">
        <Field label="Name"><input className="input" required value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="Folder" hint="Type a new name to create a folder">
          <input className="input" list="folders" value={folder} onChange={(e) => setFolder(e.target.value)} placeholder="No folder" />
          <datalist id="folders">{folders.map((f) => <option key={f}>{f}</option>)}</datalist>
        </Field>
        <Field label="Tags" hint="Comma separated, e.g. diwali, offers">
          <input className="input" value={tags} onChange={(e) => setTags(e.target.value)} />
        </Field>
        {error && <div className="rounded-lg bg-danger/15 text-danger text-sm px-3 py-2">{error}</div>}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn-primary" disabled={mutation.isPending}>
            {mutation.isPending ? <Spinner className="h-4 w-4" /> : 'Save'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

// Delete confirmation that first fetches where the asset is used, so the
// user sees which playlists will lose it before confirming (soft delete).
function DeleteModal({ asset, onClose }) {
  const queryClient = useQueryClient()
  const [error, setError] = useState(null)
  const usage = useQuery({
    queryKey: ['media', asset.id, 'usage'],
    queryFn: () => api.get(`/media/${asset.id}/usage`).then((r) => r.data),
  })
  const mutation = useMutation({
    mutationFn: () => api.delete(`/media/${asset.id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['media'] })
      onClose()
    },
    onError: (err) => setError(errorMessage(err)),
  })
  const playlists = usage.data?.playlists || []
  return (
    <Modal open onClose={onClose} title="Delete media">
      <p className="text-sm text-txt-secondary">
        Delete <span className="font-semibold">"{asset.name}"</span>? The file is removed from the library
        (soft delete) and will stop playing everywhere.
      </p>
      {usage.isLoading ? (
        <Skeleton className="h-10 w-full mt-3" />
      ) : playlists.length > 0 ? (
        <div className="mt-3 rounded-lg bg-warning/15 text-warning text-sm px-3 py-2.5">
          <p className="font-bold">Used in {playlists.length} playlist{playlists.length > 1 ? 's' : ''}:</p>
          <ul className="list-disc ml-5 mt-1">
            {playlists.map((p) => <li key={p.id}>{p.name}</li>)}
          </ul>
        </div>
      ) : (
        <p className="text-xs text-txt-muted mt-3">Not used in any playlist.</p>
      )}
      {error && <div className="mt-3 rounded-lg bg-danger/15 text-danger text-sm px-3 py-2">{error}</div>}
      <div className="mt-6 flex justify-end gap-2">
        <button className="btn-ghost" onClick={onClose}>Cancel</button>
        <button className="btn-danger" onClick={() => mutation.mutate()} disabled={mutation.isPending}>
          {mutation.isPending ? <Spinner className="h-4 w-4 border-danger/40 border-t-danger" /> : 'Delete anyway'}
        </button>
      </div>
    </Modal>
  )
}

export default function MediaPage() {
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const fileInputRef = useRef(null)
  const [dragOver, setDragOver] = useState(false)
  const [uploads, setUploads] = useState([]) // {id, name, progress, status, error}
  const [filters, setFilters] = useState({ search: '', type: '', tag: '', folder: '', mine: false })
  const [preview, setPreview] = useState(null)
  const [editing, setEditing] = useState(null)
  const [deleting, setDeleting] = useState(null)

  const media = useQuery({ queryKey: ['media'], queryFn: () => api.get('/media').then((r) => r.data) })
  const folders = useQuery({ queryKey: ['media', 'folders'], queryFn: () => api.get('/media/folders').then((r) => r.data) })
  const tags = useQuery({ queryKey: ['media', 'tags'], queryFn: () => api.get('/media/tags').then((r) => r.data) })

  const canEdit = hasRole(user, 'CONTENT_MANAGER')

  // Upload each picked/dropped file as multipart form data; every file gets
  // its own progress row, and finished rows fade out after a few seconds.
  const uploadFiles = useCallback(
    (files) => {
      ;[...files].forEach((file) => {
        const id = crypto.randomUUID()
        setUploads((u) => [...u, { id, name: file.name, progress: 0, status: 'uploading', error: null }])
        const form = new FormData()
        form.append('file', file)
        if (filters.folder) form.append('folder', filters.folder)
        api
          .post('/media', form, {
            onUploadProgress: (e) => {
              const pct = e.total ? Math.round((e.loaded / e.total) * 100) : 0
              setUploads((u) => u.map((x) => (x.id === id ? { ...x, progress: pct } : x)))
            },
          })
          .then(() => {
            setUploads((u) => u.map((x) => (x.id === id ? { ...x, status: 'done', progress: 100 } : x)))
            queryClient.invalidateQueries({ queryKey: ['media'] })
            setTimeout(() => setUploads((u) => u.filter((x) => x.id !== id)), 4000)
          })
          .catch((err) => {
            setUploads((u) =>
              u.map((x) => (x.id === id ? { ...x, status: 'error', error: errorMessage(err, 'Upload failed') } : x)),
            )
          })
      })
    },
    [filters.folder, queryClient],
  )

  // Client-side filtering over the whole library.
  const filtered = (media.data || []).filter((m) => {
    if (filters.search && !m.name.toLowerCase().includes(filters.search.toLowerCase())) return false
    if (filters.type && m.type !== filters.type) return false
    if (filters.tag && !(m.tags || []).includes(filters.tag)) return false
    if (filters.folder && m.folder !== filters.folder) return false
    if (filters.mine && m.uploadedBy?.id !== user.id) return false
    return true
  })

  return (
    <div
      onDragOver={(e) => {
        if (canEdit) {
          e.preventDefault()
          setDragOver(true)
        }
      }}
      onDragLeave={(e) => {
        if (e.currentTarget.contains(e.relatedTarget)) return
        setDragOver(false)
      }}
      onDrop={(e) => {
        if (canEdit) {
          e.preventDefault()
          setDragOver(false)
          uploadFiles(e.dataTransfer.files)
        }
      }}
      className={clsx('min-h-[70vh] rounded-xl transition-colors', dragOver && 'ring-4 ring-primary-500/40 bg-primary-500/10')}
    >
      <PageHeader
        title="Media Library"
        subtitle={`${filtered.length} asset${filtered.length === 1 ? '' : 's'} · drag files anywhere to upload (max 500 MB)`}
        actions={
          canEdit && (
            <>
              <input
                ref={fileInputRef}
                type="file"
                multiple
                accept=".mp4,.webm,.jpg,.jpeg,.png,.webp,.pdf"
                className="hidden"
                onChange={(e) => {
                  uploadFiles(e.target.files)
                  e.target.value = ''
                }}
              />
              <button className="btn-primary" onClick={() => fileInputRef.current?.click()}>
                <UploadCloud size={16} /> Upload
              </button>
            </>
          )
        }
      />

      {uploads.length > 0 && (
        <Card className="p-4 mb-4 space-y-2.5">
          {uploads.map((u) => (
            <div key={u.id}>
              <div className="flex justify-between text-xs font-semibold mb-1">
                <span className="text-txt-secondary truncate">{u.name}</span>
                <span className={u.status === 'error' ? 'text-danger' : 'text-txt-muted'}>
                  {u.status === 'error' ? u.error : u.status === 'done' ? 'Done' : `${u.progress}%`}
                </span>
              </div>
              <div className="h-1.5 rounded-full bg-white/10 overflow-hidden">
                <div
                  className={clsx('h-full transition-all', u.status === 'error' ? 'bg-danger' : u.status === 'done' ? 'bg-success' : 'bg-grad-primary')}
                  style={{ width: `${u.progress}%` }}
                />
              </div>
            </div>
          ))}
        </Card>
      )}

      <Card className="p-4 mb-4">
        <div className="flex flex-wrap items-center gap-3">
          <div className="relative flex-1 min-w-[180px]">
            <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-txt-muted" />
            <input className="input pl-9" placeholder="Search media…" value={filters.search} onChange={(e) => setFilters((f) => ({ ...f, search: e.target.value }))} />
          </div>
          <select className="input max-w-[130px]" value={filters.type} onChange={(e) => setFilters((f) => ({ ...f, type: e.target.value }))}>
            <option value="">All types</option>
            <option value="VIDEO">Videos</option>
            <option value="IMAGE">Images</option>
            <option value="PDF">PDFs</option>
          </select>
          <select className="input max-w-[150px]" value={filters.tag} onChange={(e) => setFilters((f) => ({ ...f, tag: e.target.value }))}>
            <option value="">All tags</option>
            {(tags.data || []).map((t) => <option key={t}>{t}</option>)}
          </select>
          <select className="input max-w-[160px]" value={filters.folder} onChange={(e) => setFilters((f) => ({ ...f, folder: e.target.value }))}>
            <option value="">All folders</option>
            {(folders.data || []).map((f) => <option key={f}>{f}</option>)}
          </select>
          <label className="flex items-center gap-2 text-sm text-txt-secondary font-medium cursor-pointer">
            <input type="checkbox" checked={filters.mine} onChange={(e) => setFilters((f) => ({ ...f, mine: e.target.checked }))} />
            Uploaded by me
          </label>
        </div>
      </Card>

      {media.isLoading ? (
        <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-5 gap-4">
          {[...Array(10)].map((_, i) => <Skeleton key={i} className="aspect-video w-full" />)}
        </div>
      ) : filtered.length === 0 ? (
        <Card>
          <EmptyState
            icon={ImageIcon}
            title={media.data?.length ? 'Nothing matches your filters' : 'The library is empty'}
            hint={canEdit ? 'Drop mp4, webm, jpg, png, webp or pdf files anywhere on this page to upload them.' : 'Content managers can upload media here.'}
            action={canEdit && (
              <button className="btn-primary" onClick={() => fileInputRef.current?.click()}>
                <UploadCloud size={16} /> Upload files
              </button>
            )}
          />
        </Card>
      ) : (
        <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-5 gap-4">
          {filtered.map((m) => (
            <Card key={m.id} className="group overflow-hidden">
              <div className="relative aspect-video">
                <Thumb asset={m} className="absolute inset-0 h-full w-full" />
                {m.type === 'VIDEO' && m.durationSeconds && (
                  <span className="absolute bottom-1.5 right-1.5 rounded bg-card-inner/80 text-white text-[10px] font-bold px-1.5 py-0.5">
                    {fmtSeconds(m.durationSeconds)}
                  </span>
                )}
                <div className="absolute inset-0 bg-black/0 group-hover:bg-black/60 transition-colors flex items-center justify-center gap-2 opacity-0 group-hover:opacity-100">
                  <button className="rounded-lg bg-card/90 text-txt-primary p-2 hover:bg-card" title="Preview" onClick={() => setPreview(m)}>
                    {m.type === 'VIDEO' ? <Play size={15} /> : <Eye size={15} />}
                  </button>
                  {canEdit && (
                    <>
                      <button className="rounded-lg bg-card/90 text-txt-primary p-2 hover:bg-card" title="Edit" onClick={() => setEditing(m)}>
                        <Pencil size={15} />
                      </button>
                      <button className="rounded-lg bg-card/90 p-2 hover:bg-card text-danger" title="Delete" onClick={() => setDeleting(m)}>
                        <Trash2 size={15} />
                      </button>
                    </>
                  )}
                </div>
              </div>
              <div className="p-3">
                <p className="text-sm font-semibold text-txt-primary truncate" title={m.name}>{m.name}</p>
                <p className="text-[11px] text-txt-muted mt-0.5">
                  {TYPE_LABEL[m.type]} · {fmtBytes(m.sizeBytes)} · {timeAgo(m.uploadedAt)}
                </p>
                {(m.folder || m.tags?.length > 0) && (
                  <div className="flex flex-wrap items-center gap-1 mt-1.5">
                    {m.folder && (
                      <span className="inline-flex items-center gap-0.5 text-[10px] font-semibold text-txt-secondary bg-hover rounded px-1.5 py-0.5">
                        <FolderOpen size={10} /> {m.folder}
                      </span>
                    )}
                    {(m.tags || []).slice(0, 2).map((t) => (
                      <span key={t} className="inline-flex items-center gap-0.5 text-[10px] font-semibold text-primary-400 bg-primary-500/10 rounded px-1.5 py-0.5">
                        <Tag size={10} /> {t}
                      </span>
                    ))}
                  </div>
                )}
              </div>
            </Card>
          ))}
        </div>
      )}

      <PreviewModal asset={preview} onClose={() => setPreview(null)} />
      {editing && <EditModal asset={editing} folders={folders.data || []} onClose={() => setEditing(null)} />}
      {deleting && <DeleteModal asset={deleting} onClose={() => setDeleting(null)} />}
    </div>
  )
}
