// HTTP layer for the TV player. Players never log in with email/password —
// after pairing they hold a permanent device token which is sent on every
// call in the X-Device-Token header. The paired identity (screen id + token)
// lives in localStorage so the player survives reboots.
//
// WHY raw fetch() and not the portal's shared axios client: the axios client
// belongs to the logged-in USER side of the app. It attaches the user's JWT,
// and its interceptor tries to refresh the user session on a 401. A TV must
// never send a user token, and a device 401 means "this screen was unpaired /
// deleted" — not "refresh the session". Keeping the player on plain fetch()
// guarantees the two auth worlds can never leak into each other.
import { API_BASE } from '../api/client'

// localStorage key where the pairing lives today.
const DEVICE_KEY = 'screenpilot.player.device'
// The pre-rebrand key: devices paired before the rename stored their token
// under this name, so we still look there once and migrate what we find.
const LEGACY_DEVICE_KEY = 'apnamart.player.device'

// Read the saved pairing (screen id + device token) from localStorage.
export function loadDevice() {
  try {
    // keep devices paired across the rebrand
    // Try the new key first; if it's empty, fall back to the pre-rebrand key.
    // JSON.parse('null') returns null, so a totally fresh device yields null.
    const device = JSON.parse(localStorage.getItem(DEVICE_KEY) || localStorage.getItem(LEGACY_DEVICE_KEY) || 'null')
    // One-time migration: if the pairing was only under the old key, copy it
    // to the new key and delete the old one — next boot reads the new key only.
    if (device && !localStorage.getItem(DEVICE_KEY)) {
      localStorage.setItem(DEVICE_KEY, JSON.stringify(device))
      localStorage.removeItem(LEGACY_DEVICE_KEY)
    }
    return device
  } catch {
    // Corrupted JSON in storage — treat as "not paired" rather than crash.
    return null
  }
}

// Persist (or clear, with null) the pairing in localStorage.
// saveDevice(null) is how unpair works: remove the key entirely.
export function saveDevice(device) {
  if (device) localStorage.setItem(DEVICE_KEY, JSON.stringify(device))
  else localStorage.removeItem(DEVICE_KEY)
}

// Tiny fetch wrapper: JSON in/out, optional device-token header, throws an
// Error carrying the HTTP status so callers can react to 401 (unpaired).
async function request(path, { method = 'GET', body, deviceToken } = {}) {
  const res = await fetch(`${API_BASE}/api${path}`, {
    method,
    // Headers are built conditionally with spread: only send Content-Type
    // when there IS a body, and only send X-Device-Token when we have one
    // (the pairing endpoints run before any token exists).
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(deviceToken ? { 'X-Device-Token': deviceToken } : {}),
    },
    // fetch wants a string, so JS objects are serialized here.
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) {
    // Attach the HTTP status to the Error object. Callers (PlayerPage) check
    // err.status === 401 to detect "this device was unpaired/deleted".
    const err = new Error(`Request failed: ${res.status}`)
    err.status = res.status
    throw err
  }
  // Some endpoints return an empty body (e.g. heartbeat acks). res.json()
  // would throw on empty input, so read as text and only parse if non-empty.
  const text = await res.text()
  return text ? JSON.parse(text) : null
}

// The four player endpoints: ask for a pairing code, poll until an admin
// claims it, send heartbeats, and fetch the playback config.
export const playerApi = {
  // POST the browser's user-agent so the admin can see what device is pairing.
  requestPairCode: (deviceInfo) => request('/player/pair/request', { method: 'POST', body: { deviceInfo } }),
  // GET the pairing status for a code; returns PENDING/PAIRED/EXPIRED.
  pollPairing: (code) => request(`/player/pair/poll/${code}`),
  // POST the status snapshot (HTTP fallback when the WebSocket is down).
  heartbeat: (deviceToken, payload) => request('/player/heartbeat', { method: 'POST', body: payload, deviceToken }),
  // GET everything this screen should play: schedules, playlists, layouts, requiredMedia.
  config: (deviceToken) => request('/player/config', { deviceToken }),
}

// How much browser storage the player is using vs allowed, in MB — reported
// back to the portal in heartbeats. Uses the StorageManager API when present.
export async function storageEstimateMb() {
  try {
    // navigator.storage.estimate() gives usage/quota in bytes; convert to MB.
    const est = await navigator.storage.estimate()
    return {
      usedMb: est.usage != null ? est.usage / (1024 * 1024) : null,
      totalMb: est.quota != null ? est.quota / (1024 * 1024) : null,
    }
  } catch {
    // Older TV browsers may not support the StorageManager API — report
    // "unknown" (nulls) instead of failing the heartbeat.
    return { usedMb: null, totalMb: null }
  }
}
