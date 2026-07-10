/** Minimal IndexedDB wrapper for the player: cached media blobs + queued proof-of-play logs. */

const DB_NAME = 'screenpilot-player'
const DB_VERSION = 1

let dbPromise = null

function open() {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION)
      req.onupgradeneeded = () => {
        const db = req.result
        if (!db.objectStoreNames.contains('media')) db.createObjectStore('media')
        if (!db.objectStoreNames.contains('logs')) db.createObjectStore('logs', { autoIncrement: true })
      }
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => reject(req.error)
    })
  }
  return dbPromise
}

function tx(db, store, mode, fn) {
  return new Promise((resolve, reject) => {
    const t = db.transaction(store, mode)
    const s = t.objectStore(store)
    const result = fn(s)
    t.oncomplete = () => resolve(result?.result !== undefined ? result.result : undefined)
    t.onerror = () => reject(t.error)
    t.onabort = () => reject(t.error)
  })
}

export const playerDb = {
  async getMedia(id) {
    const db = await open()
    return new Promise((resolve, reject) => {
      const req = db.transaction('media').objectStore('media').get(id)
      req.onsuccess = () => resolve(req.result || null)
      req.onerror = () => reject(req.error)
    })
  },
  async putMedia(id, blob) {
    const db = await open()
    return tx(db, 'media', 'readwrite', (s) => s.put(blob, id))
  },
  async deleteMedia(id) {
    const db = await open()
    return tx(db, 'media', 'readwrite', (s) => s.delete(id))
  },
  async mediaKeys() {
    const db = await open()
    return new Promise((resolve, reject) => {
      const req = db.transaction('media').objectStore('media').getAllKeys()
      req.onsuccess = () => resolve(req.result || [])
      req.onerror = () => reject(req.error)
    })
  },
  async clearMedia() {
    const db = await open()
    return tx(db, 'media', 'readwrite', (s) => s.clear())
  },

  async addLog(entry) {
    const db = await open()
    return tx(db, 'logs', 'readwrite', (s) => s.add(entry))
  },
  /** Returns [{key, value}] oldest-first, up to limit. */
  async peekLogs(limit = 200) {
    const db = await open()
    return new Promise((resolve, reject) => {
      const out = []
      const req = db.transaction('logs').objectStore('logs').openCursor()
      req.onsuccess = () => {
        const cursor = req.result
        if (cursor && out.length < limit) {
          out.push({ key: cursor.key, value: cursor.value })
          cursor.continue()
        } else {
          resolve(out)
        }
      }
      req.onerror = () => reject(req.error)
    })
  },
  async deleteLogs(keys) {
    if (!keys.length) return
    const db = await open()
    return tx(db, 'logs', 'readwrite', (s) => {
      keys.forEach((k) => s.delete(k))
    })
  },
}
