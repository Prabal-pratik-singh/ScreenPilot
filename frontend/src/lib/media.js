// Media helpers shared by the portal and the player: building signed URLs for
// media files/thumbnails, icon + label lookups per media type, working out how
// long a playlist item should stay on screen, and extracting YouTube video ids.
import { API_BASE } from '../api/client'
import { Film, Image as ImageIcon, FileText } from 'lucide-react'

// Media binaries need HMAC-signed links (?exp=&sig=) issued by the API —
// use the signed relative URLs the DTOs carry, prefixed with the API base.
export const mediaThumbSrc = (asset) => (asset?.thumbUrl ? `${API_BASE}${asset.thumbUrl}` : null)
export const mediaFileSrc = (asset) => (asset?.fileUrl ? `${API_BASE}${asset.fileUrl}` : null)

export const TYPE_ICON = { VIDEO: Film, IMAGE: ImageIcon, PDF: FileText }
export const TYPE_LABEL = { VIDEO: 'Video', IMAGE: 'Image', PDF: 'PDF' }

/** Same rules as the backend: videos full length, others use configured duration. */
export function effectiveItemDuration(item) {
  if (item.itemType === 'MEDIA' && item.media) {
    if (item.media.type === 'VIDEO') return item.media.durationSeconds ?? 30
    return item.durationSeconds ?? 10
  }
  return item.durationSeconds ?? 20
}

/** Pulls the 11-char video id out of any common YouTube URL shape, else null. */
export function youTubeId(url) {
  const m = String(url || '').match(
    /(?:youtube\.com\/(?:watch\?v=|embed\/|shorts\/)|youtu\.be\/)([\w-]{6,})/,
  )
  return m ? m[1] : null
}
