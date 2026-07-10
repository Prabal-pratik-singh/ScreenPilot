import axios from 'axios'

// Empty string = same-origin (Docker/nginx serves the app and proxies /api + /ws)
const configured = import.meta.env.VITE_API_BASE_URL
export const API_BASE = configured !== undefined ? configured : 'http://localhost:8081'
export const WS_URL = API_BASE
  ? API_BASE.replace(/^http/, 'ws') + '/ws'
  : `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws`

const TOKENS_KEY = 'screenpilot.tokens'
const LEGACY_TOKENS_KEY = 'apnamart.tokens'

let tokens = null
try {
  // migrate sessions stored under the pre-rebrand key
  tokens = JSON.parse(localStorage.getItem(TOKENS_KEY) || localStorage.getItem(LEGACY_TOKENS_KEY) || 'null')
  if (tokens && !localStorage.getItem(TOKENS_KEY)) {
    localStorage.setItem(TOKENS_KEY, JSON.stringify(tokens))
    localStorage.removeItem(LEGACY_TOKENS_KEY)
  }
} catch {
  tokens = null
}

export function setTokens(next) {
  tokens = next
  if (next) localStorage.setItem(TOKENS_KEY, JSON.stringify(next))
  else localStorage.removeItem(TOKENS_KEY)
}

export function getAccessToken() {
  return tokens?.accessToken || null
}

export const api = axios.create({ baseURL: `${API_BASE}/api` })

api.interceptors.request.use((config) => {
  if (tokens?.accessToken) {
    config.headers.Authorization = `Bearer ${tokens.accessToken}`
  }
  return config
})

let refreshPromise = null

api.interceptors.response.use(
  (res) => res,
  async (error) => {
    const original = error.config
    const status = error.response?.status
    const isAuthCall = original?.url?.includes('/auth/')
    if (status === 401 && original && !original._retry && !isAuthCall && tokens?.refreshToken) {
      original._retry = true
      try {
        refreshPromise =
          refreshPromise ||
          axios
            .post(`${API_BASE}/api/auth/refresh`, { refreshToken: tokens.refreshToken })
            .then((r) => r.data)
            .finally(() => {
              refreshPromise = null
            })
        const data = await refreshPromise
        setTokens({ accessToken: data.accessToken, refreshToken: data.refreshToken })
        original.headers.Authorization = `Bearer ${data.accessToken}`
        return api(original)
      } catch {
        setTokens(null)
        window.dispatchEvent(new Event('auth:logout'))
      }
    }
    return Promise.reject(error)
  },
)

/** Human-readable message out of our consistent API error shape. */
export function errorMessage(error, fallback = 'Something went wrong') {
  const data = error?.response?.data
  if (data?.fieldErrors) {
    const first = Object.entries(data.fieldErrors)[0]
    if (first) return `${first[0]}: ${first[1]}`
  }
  return data?.message || error?.message || fallback
}
