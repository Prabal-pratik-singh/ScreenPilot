// Central HTTP client for the whole portal, built on axios.
// It owns three jobs: (1) figure out where the API and WebSocket live,
// (2) keep the login tokens in localStorage and attach them to every request,
// (3) silently refresh an expired access token and retry the failed request.
import axios from 'axios'

// Empty string = same-origin (Docker/nginx serves the app and proxies /api + /ws)
// Vite replaces `import.meta.env.VITE_API_BASE_URL` with a literal value while
// building the bundle — it is baked in at build time, not read at runtime.
const configured = import.meta.env.VITE_API_BASE_URL
// Why `!== undefined` instead of `configured || fallback`: an EMPTY STRING is a
// real, deliberate setting meaning "same origin — call the host that served
// this page". A truthiness check would treat '' as missing and wrongly fall
// back to localhost. Only when the variable is not set at all (running the dev
// server without Docker) do we default to the local backend on port 8081.
export const API_BASE = configured !== undefined ? configured : 'http://localhost:8081'
// The WebSocket URL is derived from API_BASE so both always target the same
// backend. Two cases:
// - API_BASE is a full URL (dev): swap the leading "http" for "ws". Because
//   only the "http" prefix is replaced, "https://..." naturally becomes
//   "wss://..." (secure WebSocket) — one regex handles both schemes.
// - API_BASE is '' (same-origin production): there is no URL to transform, so
//   build one from the page's own address. Pages served over https MUST use
//   wss — browsers block plain ws connections from secure pages.
export const WS_URL = API_BASE
  ? API_BASE.replace(/^http/, 'ws') + '/ws'
  : `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws`

// localStorage keys for the saved token pair: the current key, plus the
// pre-rebrand key that older browsers may still hold a session under.
const TOKENS_KEY = 'screenpilot.tokens'
const LEGACY_TOKENS_KEY = 'apnamart.tokens'

// Module-level in-memory copy of the token pair — every request reads this
// variable, while localStorage is only the durable backup that lets a session
// survive a page reload.
let tokens = null
try {
  // migrate sessions stored under the pre-rebrand key
  // Try the current key first, then the pre-rebrand key. The trailing 'null'
  // string keeps JSON.parse happy when neither key exists (it parses to null).
  tokens = JSON.parse(localStorage.getItem(TOKENS_KEY) || localStorage.getItem(LEGACY_TOKENS_KEY) || 'null')
  // Tokens found only under the old key? Copy them to the new key and delete
  // the old entry — a one-time migration so nobody is logged out by the rename.
  if (tokens && !localStorage.getItem(TOKENS_KEY)) {
    localStorage.setItem(TOKENS_KEY, JSON.stringify(tokens))
    localStorage.removeItem(LEGACY_TOKENS_KEY)
  }
} catch {
  // Unreadable/corrupted JSON in storage — safest answer is "not logged in".
  tokens = null
}

// Save (or clear, when passed null) the access/refresh token pair, both in
// memory and in localStorage so a page reload keeps the session.
export function setTokens(next) {
  tokens = next
  if (next) localStorage.setItem(TOKENS_KEY, JSON.stringify(next))
  else localStorage.removeItem(TOKENS_KEY)
}

// Read-only peek at the current access token. AuthContext calls this on
// startup to decide whether a stored session might exist at all.
export function getAccessToken() {
  return tokens?.accessToken || null
}

// The single axios instance the whole portal shares. With baseURL set, a call
// like api.get('/screens') really requests `<API_BASE>/api/screens`; when
// API_BASE is '' the URL is relative, i.e. same-origin through the proxy.
export const api = axios.create({ baseURL: `${API_BASE}/api` })

// Request interceptor: runs before every API call and adds the
// "Authorization: Bearer <accessToken>" header when we are logged in.
api.interceptors.request.use((config) => {
  // Reads the in-memory `tokens`, so this hot path never touches localStorage.
  if (tokens?.accessToken) {
    config.headers.Authorization = `Bearer ${tokens.accessToken}`
  }
  return config
})

// Shared in-flight refresh call. If several requests hit 401 at the same time
// they all wait on this one promise instead of firing multiple refreshes.
// Why that matters: the backend ROTATES refresh tokens — each refresh call
// kills the token it was given and issues a new one. Parallel refreshes would
// therefore invalidate each other and force a logout; this module-level
// singleton guarantees exactly ONE refresh no matter how many 401s land at once.
let refreshPromise = null

// Response interceptor: the token-refresh flow.
// On a 401 (access token expired) we call /auth/refresh once, store the new
// tokens, and replay the original request with the fresh access token.
api.interceptors.response.use(
  // Successful responses pass through untouched.
  (res) => res,
  async (error) => {
    // `original` is the exact config of the request that failed — kept so we
    // can replay the very same request once we hold a fresh token.
    const original = error.config
    const status = error.response?.status
    // /auth/* requests are excluded from the retry logic below: refreshing in
    // response to a failed login/refresh would recurse forever.
    const isAuthCall = original?.url?.includes('/auth/')
    // Only refresh once per request (_retry flag), never for /auth/* calls
    // themselves (a failed login/refresh should not loop), and only when we
    // actually hold a refresh token.
    if (status === 401 && original && !original._retry && !isAuthCall && tokens?.refreshToken) {
      // Stamp the request BEFORE refreshing: if the replay below also comes
      // back 401, the flag stops us from refreshing again for the same request.
      original._retry = true
      try {
        // Reuse the in-flight refresh if one exists, otherwise start it.
        // Note it uses the BARE axios, not our `api` instance — going through
        // `api` would run this same interceptor on the refresh call itself.
        refreshPromise =
          refreshPromise ||
          axios
            .post(`${API_BASE}/api/auth/refresh`, { refreshToken: tokens.refreshToken })
            .then((r) => r.data)
            // Clear the slot when done (success or failure) so the NEXT expiry,
            // an hour from now, starts a fresh refresh instead of reusing this one.
            .finally(() => {
              refreshPromise = null
            })
        // Every request that piled up on the 401 resumes here with the same
        // freshly issued token pair.
        const data = await refreshPromise
        // Store the ROTATED pair: the refresh token we just spent is now dead
        // and the response carries its replacement.
        setTokens({ accessToken: data.accessToken, refreshToken: data.refreshToken })
        // Overwrite the stale Authorization header the failed attempt carried.
        original.headers.Authorization = `Bearer ${data.accessToken}`
        // Replay the original request with the new token.
        // Returning api(original) makes the caller's own `await` resolve with
        // the replayed response — the token expiry is invisible to page code.
        return api(original)
      } catch {
        // Refresh failed too (refresh token expired/revoked): drop the session
        // and tell AuthContext to log the user out via a window event.
        // A DOM event is used because this file is a plain JS module — it can't
        // call React hooks or reach into React context. window.dispatchEvent is
        // the simplest one-way bridge into React: AuthContext registers a
        // listener for 'auth:logout' and clears the user when it fires.
        setTokens(null)
        window.dispatchEvent(new Event('auth:logout'))
      }
    }
    // Anything not handled above (non-401s, /auth/* failures, already-retried
    // requests) is re-thrown so the calling code's own .catch() still runs.
    return Promise.reject(error)
  },
)

/** Human-readable message out of our consistent API error shape. */
// The backend wraps every error in one uniform JSON shape:
//   { message: "...", fieldErrors?: { fieldName: "what is wrong with it" } }
// so no screen ever has to guess how to display a failure.
export function errorMessage(error, fallback = 'Something went wrong') {
  // error.response is missing entirely for network failures (server down,
  // no connection) — hence the optional chaining at every step.
  const data = error?.response?.data
  // Validation errors carry a per-field map; show just the first one as
  // "field: problem" to keep the message short.
  if (data?.fieldErrors) {
    const first = Object.entries(data.fieldErrors)[0]
    if (first) return `${first[0]}: ${first[1]}`
  }
  // Fallback chain: server-provided message, then the axios-level message
  // (e.g. "Network Error"), then the caller-supplied generic default.
  return data?.message || error?.message || fallback
}
