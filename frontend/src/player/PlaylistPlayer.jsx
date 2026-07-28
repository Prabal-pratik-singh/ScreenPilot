import { useCallback, useEffect, useRef, useState } from 'react'
import { youTubeId } from '../lib/media'

/**
 * Round-robin playlist renderer with a double buffer: two stacked layers,
 * the next item preloads hidden, layers swap by opacity so transitions are
 * seamless. Videos advance on `ended`; images/PDF/external use timers.
 */

const FADE_MS = 400

// How long an item stays on screen. Videos return null (we wait for the
// `ended` event instead of a timer); everything else gets a fixed duration.
function effectiveSeconds(item) {
  if (item.itemType === 'MEDIA' && item.media?.type === 'VIDEO') {
    return item.media.durationSeconds || null // null = wait for `ended`
  }
  return item.effectiveDurationSeconds || item.durationSeconds || (item.itemType === 'MEDIA' ? 10 : 20)
}

// Renders the actual content of one buffer layer (video / image / PDF /
// YouTube / web page). `visible` says whether this layer is currently the
// front one; the hidden layer is where the next item preloads.
function SlotContent({ slot, visible, onVideoEnded, onUrlFail }) {
  const videoRef = useRef(null)
  const [urlLoaded, setUrlLoaded] = useState(false)

  // Play/pause the slot's video as it becomes (in)visible
  useEffect(() => {
    const v = videoRef.current
    if (!v) return
    if (visible) {
      try {
        v.currentTime = 0
      } catch {
        /* not seekable yet */
      }
      v.play().catch(() => {})
    } else {
      v.pause()
    }
  }, [visible, slot?.uid])

  // Live-URL load watchdog: if the iframe hasn't loaded within 12s while visible, skip
  useEffect(() => {
    if (!slot || slot.item.itemType !== 'URL' || !visible) return undefined
    setUrlLoaded(false)
    const t = setTimeout(() => {
      if (!urlLoaded) onUrlFail?.()
    }, 12000)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slot?.uid, visible])

  if (!slot) return null
  const { item, url } = slot

  if (item.itemType === 'MEDIA') {
    const type = item.media?.type
    if (type === 'VIDEO') {
      return (
        <video
          ref={videoRef}
          src={url}
          muted
          playsInline
          preload="auto"
          className="h-full w-full object-contain"
          onEnded={() => visible && onVideoEnded?.()}
          onError={() => visible && onVideoEnded?.()}
        />
      )
    }
    if (type === 'IMAGE') {
      return <img src={url} alt="" className="h-full w-full object-contain" draggable={false} />
    }
    if (type === 'PDF') {
      return (
        <iframe
          title={item.title || 'document'}
          src={url ? `${url}#toolbar=0&navpanes=0&view=Fit` : undefined}
          className="h-full w-full bg-white border-0"
        />
      )
    }
  }
  if (item.itemType === 'YOUTUBE') {
    const id = youTubeId(item.url)
    if (!id) return null
    const src = `https://www.youtube.com/embed/${id}?autoplay=${visible ? 1 : 0}&mute=1&controls=0&loop=1&playlist=${id}&modestbranding=1&rel=0&playsinline=1`
    // only mount the iframe when visible so autoplay starts on cue
    return visible ? <iframe title="youtube" src={src} className="h-full w-full border-0" allow="autoplay; encrypted-media" /> : null
  }
  if (item.itemType === 'URL') {
    return visible ? (
      <iframe
        title="web"
        src={item.url}
        className="h-full w-full border-0 bg-white"
        onLoad={() => setUrlLoaded(true)}
        sandbox="allow-scripts allow-same-origin allow-forms"
      />
    ) : null
  }
  return null
}

// Monotonic id so every slot instance gets a unique React key/uid.
let slotUid = 0

// The playlist loop itself. Owns the double buffer: `slots` holds the two
// layers, `front` says which index (0 or 1) is on top right now.
export default function PlaylistPlayer({ items, context, dm, onLog, onNowPlaying }) {
  const [slots, setSlots] = useState([null, null])
  const [front, setFront] = useState(0)
  const indexRef = useRef(-1)
  const startedAtRef = useRef(null)
  const timerRef = useRef(null)
  const itemsRef = useRef(items)
  itemsRef.current = items

  // An item can play if its media blob is already cached (offline-safe),
  // or — for YouTube/URL items — if the device currently has network.
  const isPlayable = useCallback(
    (item) => {
      if (item.itemType === 'MEDIA') {
        return item.media && dm.isDownloaded(String(item.media.id))
      }
      return navigator.onLine // external content needs the network
    },
    [dm],
  )

  // Prepares one slot for the buffer: resolves the cached blob's object URL
  // for media items so the layer can render it instantly.
  const buildSlot = useCallback(
    async (index) => {
      const item = itemsRef.current[index]
      if (!item) return null
      let url = null
      if (item.itemType === 'MEDIA' && item.media) {
        url = await dm.getUrl(String(item.media.id))
        if (!url) return null
      }
      return { uid: `s${++slotUid}`, item, index, url }
    },
    [dm],
  )

  // Round-robin search: the first playable item after `from`, wrapping
  // around the list; -1 when nothing is playable at all.
  const nextPlayableIndex = useCallback(
    (from) => {
      const list = itemsRef.current
      if (!list.length) return -1
      for (let step = 1; step <= list.length; step++) {
        const idx = (from + step) % list.length
        if (isPlayable(list[idx])) return idx
      }
      return -1
    },
    [isPlayable],
  )

  // Emits one proof-of-play record (what played, from when to when) for the
  // item that just finished; PlayerPage queues it for upload.
  const logCurrent = useCallback(
    (item) => {
      if (!item || !startedAtRef.current) return
      const endedAt = new Date()
      onLog?.({
        itemId: item.id,
        mediaId: item.media?.id || null,
        scheduleId: context?.scheduleId || null,
        playlistId: context?.playlistId || null,
        itemTitle: item.title || item.media?.name || item.url || 'item',
        itemType: item.itemType === 'MEDIA' ? item.media?.type || 'MEDIA' : item.itemType,
        startedAt: startedAtRef.current.toISOString(),
        endedAt: endedAt.toISOString(),
      })
    },
    [onLog, context],
  )

  // advance() is recreated on every render; timers and video callbacks call
  // it through this ref so they always hit the latest version.
  const advanceRef = useRef(() => {})

  // Arms the timer that will flip to the next item once this one's duration
  // elapses (videos rely on `ended` instead, with a 30-min safety cap).
  const scheduleAdvance = useCallback((item) => {
    clearTimeout(timerRef.current)
    const seconds = effectiveSeconds(item)
    if (seconds != null) {
      timerRef.current = setTimeout(() => advanceRef.current(), Math.max(1, seconds) * 1000)
    } else if (item.itemType === 'MEDIA' && item.media?.type === 'VIDEO') {
      // safety net if `ended` never fires (codec stall): cap at 30 min
      timerRef.current = setTimeout(() => advanceRef.current(), 30 * 60 * 1000)
    }
  }, [])

  // The heart of the double buffer. Each call:
  //   1. logs the item that just finished,
  //   2. makes sure the hidden (back) layer holds the next item,
  //   3. flips `front` so the layers cross-fade,
  //   4. preloads the item after that into the now-hidden layer.
  const advance = useCallback(async () => {
    // Step 1: proof-of-play for the outgoing item.
    const currentSlot = slots[front]
    if (currentSlot) logCurrent(currentSlot.item)

    // Step 2: the back layer should already be preloaded from last time.
    const backIdx = 1 - front
    let backSlot = slots[backIdx]
    // if the preloaded slot went stale (playlist changed), rebuild it
    if (!backSlot || itemsRef.current[backSlot.index] !== backSlot.item) {
      const idx = nextPlayableIndex(indexRef.current)
      if (idx === -1) return
      backSlot = await buildSlot(idx)
      if (!backSlot) return
    }

    // Step 3: flip the buffers — the CSS opacity transition does the fade —
    // and arm the timer for the item that is now on screen.
    indexRef.current = backSlot.index
    startedAtRef.current = new Date()
    onNowPlaying?.(backSlot.item)
    setFront(backIdx)
    scheduleAdvance(backSlot.item)

    // Step 4: preload the following item into the now-hidden layer after the fade
    const followIdx = nextPlayableIndex(backSlot.index)
    setTimeout(async () => {
      const s = followIdx === -1 ? null : await buildSlot(followIdx)
      setSlots((prev) => {
        const next = [...prev]
        next[front] = s
        return next
      })
    }, FADE_MS + 100)
  }, [slots, front, logCurrent, nextPlayableIndex, buildSlot, onNowPlaying, scheduleAdvance])

  advanceRef.current = advance

  // (re)initialize when the playable set changes
  // contentKey fingerprints "what could play right now" (schedule, item list,
  // which items are cached); the init effect below re-runs when it changes.
  const playableKey = items
    .map((it, i) => (isPlayable(it) ? i : null))
    .filter((x) => x != null)
    .join(',')
  const contentKey = `${context?.scheduleId || ''}:${items.map((i) => i.id).join('|')}:${playableKey}`

  useEffect(() => {
    let cancelled = false
    clearTimeout(timerRef.current)
    // Fresh start: build the first slot, show it, preload the second.
    const init = async () => {
      const firstIdx = nextPlayableIndex(-1)
      if (firstIdx === -1) {
        setSlots([null, null])
        onNowPlaying?.(null)
        return
      }
      const first = await buildSlot(firstIdx)
      if (cancelled || !first) return
      indexRef.current = firstIdx
      startedAtRef.current = new Date()
      onNowPlaying?.(first.item)
      const secondIdx = nextPlayableIndex(firstIdx)
      const second = secondIdx === -1 ? null : await buildSlot(secondIdx)
      if (cancelled) return
      setSlots([first, second])
      setFront(0)
      scheduleAdvance(first.item)
    }
    init()
    return () => {
      cancelled = true
      clearTimeout(timerRef.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [contentKey])

  const anyPlayable = items.some(isPlayable)
  if (!anyPlayable) return null

  return (
    <div className="absolute inset-0 bg-black">
      {/* The two stacked buffer layers; only opacity/z-index change on flip */}
      {[0, 1].map((i) => (
        <div
          key={i}
          className="absolute inset-0 transition-opacity"
          style={{ opacity: front === i ? 1 : 0, transitionDuration: `${FADE_MS}ms`, zIndex: front === i ? 2 : 1 }}
        >
          <SlotContent
            slot={slots[i]}
            visible={front === i}
            onVideoEnded={() => advanceRef.current()}
            onUrlFail={() => advanceRef.current()}
          />
        </div>
      ))}
    </div>
  )
}
