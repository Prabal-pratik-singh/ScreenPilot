// Login state for the portal, shared through React Context.
// AuthProvider wraps the app and exposes { user, booting, login, logout };
// the current user is cached in localStorage so a refresh doesn't log you out,
// and hasRole() answers "is this user at least an ADMIN?" style questions.
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { api, setTokens, getAccessToken } from '../api/client'

// The context object itself; `null` is what a consumer would get if no
// AuthProvider were mounted above it in the tree.
const AuthContext = createContext(null)

// localStorage key for the cached user profile (name, email, role, ...).
const USER_KEY = 'screenpilot.user'
// Numeric ranking of roles so "at least CONTENT_MANAGER" is a simple >= check.
// Higher number = more privileges; hasRole() below compares these numbers.
const ROLE_RANK = { VIEWER: 0, CONTENT_MANAGER: 1, ADMIN: 2, SUPER_ADMIN: 3 }

// Provider component mounted once in main.jsx; everything under it can call
// useAuth() to read the user or trigger login/logout.
export function AuthProvider({ children }) {
  // Seed the user from localStorage synchronously — the function form of
  // useState runs exactly once, before the first render. A page refresh
  // therefore paints the logged-in portal INSTANTLY from the cached profile
  // instead of flashing the login screen while the server check runs.
  const [user, setUser] = useState(() => {
    try {
      return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
    } catch {
      // Corrupted cache — start logged out rather than crash the app.
      return null
    }
  })
  // booting = "we hold a token but haven't re-checked it with the server yet".
  // It starts true only when a token exists; RequireAuth (App.jsx) shows a
  // spinner while booting, which prevents the flash where a logged-in user is
  // briefly redirected to /login before the check finishes. With no token
  // there is nothing to verify, so booting starts false.
  const [booting, setBooting] = useState(!!getAccessToken())

  // Effect: watches nothing (empty deps) — runs once when the provider mounts.
  // It asks the server "is this stored session still valid, and who am I?".
  useEffect(() => {
    // Revalidate the stored session on load
    if (getAccessToken()) {
      api
        .get('/auth/me')
        .then((r) => {
          // Fresh profile from the server — also overwrite the cached copy, in
          // case the name/role changed since the last visit.
          setUser(r.data)
          localStorage.setItem(USER_KEY, JSON.stringify(r.data))
        })
        // Failures are deliberately ignored: an expired access token triggers
        // the api client's silent refresh, and if even that fails the client
        // fires 'auth:logout' (handled below) which clears the user anyway.
        .catch(() => {})
        // Either way the check is over — RequireAuth can stop showing its spinner.
        .finally(() => setBooting(false))
    } else {
      // No token stored — nothing to verify, boot finishes immediately.
      setBooting(false)
    }
  }, [])

  // Effect: watches nothing — subscribes once, on mount, to the DOM event
  // bridge coming from api/client.js (a plain JS module that cannot call React
  // hooks, so a window event is its only way to talk to this context).
  useEffect(() => {
    // The API client fires "auth:logout" when a token refresh fails —
    // clear the user here so the UI drops back to the login page.
    const onLogout = () => {
      setUser(null)
      localStorage.removeItem(USER_KEY)
    }
    window.addEventListener('auth:logout', onLogout)
    // Cleanup on unmount so listeners never stack up (StrictMode mounts twice
    // in dev, which would otherwise leave a duplicate handler behind).
    return () => window.removeEventListener('auth:logout', onLogout)
  }, [])

  // Calls the login endpoint, then stores the token pair + user profile.
  // useCallback keeps the function's identity stable across renders so the
  // useMemo below (and any component depending on `login`) doesn't churn.
  const login = useCallback(async (email, password) => {
    const { data } = await api.post('/auth/login', { email, password })
    // Tokens first — from this moment the request interceptor authenticates
    // every api call. Then remember who is logged in, in state + cache.
    setTokens({ accessToken: data.accessToken, refreshToken: data.refreshToken })
    setUser(data.user)
    localStorage.setItem(USER_KEY, JSON.stringify(data.user))
    // Returned so LoginPage can navigate right after a successful login.
    return data.user
  }, [])

  // Clears tokens and cached user — a purely client-side logout.
  // No request is sent: once the tokens are gone this browser simply cannot
  // act on the session any more.
  const logout = useCallback(() => {
    setTokens(null)
    setUser(null)
    localStorage.removeItem(USER_KEY)
  }, [])

  // Memoize the context value: without useMemo a brand-new object would be
  // built on every render, and every component calling useAuth() would
  // re-render even when nothing inside actually changed.
  const value = useMemo(() => ({ user, booting, login, logout }), [user, booting, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// Hook used by pages/components to read the auth state from context.
export function useAuth() {
  return useContext(AuthContext)
}

/** True when the user's role is at least `role` in the hierarchy. */
export function hasRole(user, role) {
  // Nobody logged in → no permissions at all.
  if (!user) return false
  // Numeric comparison with FAIL-CLOSED defaults: an unknown user role ranks
  // -1 (below everything) and an unknown required role ranks 99 (above
  // everything). Either way a typo or a not-yet-mapped role DENIES access
  // instead of accidentally granting it.
  return (ROLE_RANK[user.role] ?? -1) >= (ROLE_RANK[role] ?? 99)
}
