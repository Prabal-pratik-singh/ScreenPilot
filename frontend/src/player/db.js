/** Minimal IndexedDB wrapper for the player: cached media blobs + queued proof-of-play logs. */
// IndexedDB is the browser's built-in database; it can hold large binary
// Blobs, which localStorage cannot, so downloaded videos/images live here.
// Two object stores: 'media' (key = mediaId, value = Blob) and 'logs'
// (auto-increment key, value = one proof-of-play entry).
//
// WHY hand-rolled instead of a library (idb, dexie, ...): the player only
// needs seven tiny operations, and every extra dependency is one more thing
// that can break on old TV browsers. ~100 lines of plain promises is easier
// to audit than a package, and there is nothing to keep updated.

// Database name + schema version. Bumping DB_VERSION would re-trigger
// onupgradeneeded below on every device (that's how IndexedDB migrations work).
const DB_NAME = 'screenpilot-player'
const DB_VERSION = 1

// Memoized connection: module-level variable holds the one open() promise.
let dbPromise = null

// Open the database once and reuse the same connection promise afterwards.
// onupgradeneeded creates the object stores on first run.
function open() {
  // Only the FIRST caller actually opens the DB; everyone after gets the
  // same cached promise. Even callers that arrive while opening is still in
  // progress simply await the same promise — no duplicate connections.
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      // indexedDB.open is async + event-based; we wrap it in a Promise.
      const req = indexedDB.open(DB_NAME, DB_VERSION)
      // Fires only when the DB doesn't exist yet (or version bumped):
      // the one place object stores can be created.
      req.onupgradeneeded = () => {
        const db = req.result
        // 'media': we pass our own key (mediaId string) on every put.
        if (!db.objectStoreNames.contains('media')) db.createObjectStore('media')
        // 'logs': autoIncrement means IndexedDB assigns a growing numeric
        // key to each entry — those keys later double as delete handles.
        if (!db.objectStoreNames.contains('logs')) db.createObjectStore('logs', { autoIncrement: true })
      }
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => reject(req.error)
    })
  }
  return dbPromise
}

// Run one IndexedDB transaction and resolve when it commits — turns the
// callback-style IndexedDB API into a Promise.
function tx(db, store, mode, fn) {
  return new Promise((resolve, reject) => {
    // Start a transaction on one store ('readonly' or 'readwrite').
    const t = db.transaction(store, mode)
    const s = t.objectStore(store)
    // Let the caller issue its request(s) against the store synchronously.
    const result = fn(s)
    // Resolve only when the whole transaction COMMITS (data is durable),
    // not merely when the individual request succeeds.
    t.oncomplete = () => resolve(result?.result !== undefined ? result.result : undefined)
    t.onerror = () => reject(t.error)
    // onabort covers e.g. quota-exceeded, which aborts without an error event.
    t.onabort = () => reject(t.error)
  })
}

// The public API: media get/put/delete/keys/clear + log add/peek/delete.
export const playerDb = {
  // Fetch one cached media Blob by its mediaId (null when not cached).
  // Uses a raw request (not tx()) because we need this request's own result.
  async getMedia(id) {
    const db = await open()
    return new Promise((resolve, reject) => {
      const req = db.transaction('media').objectStore('media').get(id)
      req.onsuccess = () => resolve(req.result || null)
      req.onerror = () => reject(req.error)
    })
  },
  // Store a downloaded Blob under its mediaId; put() overwrites if present.
  async putMedia(id, blob) {
    const db = await open()
    return tx(db, 'media', 'readwrite', (s) => s.put(blob, id))
  },
  // Remove one cached media (used when it is no longer required).
  async deleteMedia(id) {
    const db = await open()
    return tx(db, 'media', 'readwrite', (s) => s.delete(id))
  },
  // List all cached mediaIds — the download manager compares this list with
  // the required list to decide what to evict.
  async mediaKeys() {
    const db = await open()
    return new Promise((resolve, reject) => {
      const req = db.transaction('media').objectStore('media').getAllKeys()
      req.onsuccess = () => resolve(req.result || [])
      req.onerror = () => reject(req.error)
    })
  },
  // Wipe every cached media blob (the CLEAR_CACHE remote command).
  async clearMedia() {
    const db = await open()
    return tx(db, 'media', 'readwrite', (s) => s.clear())
  },

  // Append one proof-of-play entry; add() lets the store assign the
  // auto-increment key, so entries stay in insertion (oldest-first) order.
  async addLog(entry) {
    const db = await open()
    return tx(db, 'logs', 'readwrite', (s) => s.add(entry))
  },
  /** Returns [{key, value}] oldest-first, up to limit. */
  // Cursor-based read: getAll() would load EVERY queued log into memory at
  // once; a cursor walks entries one by one so we can stop exactly at
  // `limit`. Keys are returned too — they are the delete handles the
  // flusher passes back to deleteLogs() after a successful upload.
  async peekLogs(limit = 200) {
    const db = await open()
    return new Promise((resolve, reject) => {
      const out = []
      const req = db.transaction('logs').objectStore('logs').openCursor()
      // onsuccess fires once per row; cursor.continue() asks for the next.
      req.onsuccess = () => {
        const cursor = req.result
        if (cursor && out.length < limit) {
          out.push({ key: cursor.key, value: cursor.value })
          cursor.continue()
        } else {
          // Either no more rows (cursor is null) or we hit the limit.
          resolve(out)
        }
      }
      req.onerror = () => reject(req.error)
    })
  },
  // Delete exactly the uploaded logs by key — logs queued AFTER the peek
  // keep their higher keys and survive untouched.
  async deleteLogs(keys) {
    if (!keys.length) return
    const db = await open()
    // All deletes share one transaction: they commit (or fail) together.
    return tx(db, 'logs', 'readwrite', (s) => {
      keys.forEach((k) => s.delete(k))
    })
  },
}
