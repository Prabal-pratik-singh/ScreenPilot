import { API_BASE } from '../api/client'

const DEVICE_KEY = 'screenpilot.player.device'
const LEGACY_DEVICE_KEY = 'apnamart.player.device'

export function loadDevice() {
  try {
    // keep devices paired across the rebrand
    const device = JSON.parse(localStorage.getItem(DEVICE_KEY) || localStorage.getItem(LEGACY_DEVICE_KEY) || 'null')
    if (device && !localStorage.getItem(DEVICE_KEY)) {
      localStorage.setItem(DEVICE_KEY, JSON.stringify(device))
      localStorage.removeItem(LEGACY_DEVICE_KEY)
    }
    return device
  } catch {
    return null
  }
}

export function saveDevice(device) {
  if (device) localStorage.setItem(DEVICE_KEY, JSON.stringify(device))
  else localStorage.removeItem(DEVICE_KEY)
}

async function request(path, { method = 'GET', body, deviceToken } = {}) {
  const res = await fetch(`${API_BASE}/api${path}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(deviceToken ? { 'X-Device-Token': deviceToken } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) {
    const err = new Error(`Request failed: ${res.status}`)
    err.status = res.status
    throw err
  }
  const text = await res.text()
  return text ? JSON.parse(text) : null
}

export const playerApi = {
  requestPairCode: (deviceInfo) => request('/player/pair/request', { method: 'POST', body: { deviceInfo } }),
  pollPairing: (code) => request(`/player/pair/poll/${code}`),
  heartbeat: (deviceToken, payload) => request('/player/heartbeat', { method: 'POST', body: payload, deviceToken }),
  config: (deviceToken) => request('/player/config', { deviceToken }),
}

export async function storageEstimateMb() {
  try {
    const est = await navigator.storage.estimate()
    return {
      usedMb: est.usage != null ? est.usage / (1024 * 1024) : null,
      totalMb: est.quota != null ? est.quota / (1024 * 1024) : null,
    }
  } catch {
    return { usedMb: null, totalMb: null }
  }
}
