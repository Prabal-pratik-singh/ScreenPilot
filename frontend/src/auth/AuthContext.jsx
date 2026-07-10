import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { api, setTokens, getAccessToken } from '../api/client'

const AuthContext = createContext(null)

const USER_KEY = 'screenpilot.user'
const ROLE_RANK = { VIEWER: 0, CONTENT_MANAGER: 1, ADMIN: 2, SUPER_ADMIN: 3 }

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    try {
      return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
    } catch {
      return null
    }
  })
  const [booting, setBooting] = useState(!!getAccessToken())

  useEffect(() => {
    // Revalidate the stored session on load
    if (getAccessToken()) {
      api
        .get('/auth/me')
        .then((r) => {
          setUser(r.data)
          localStorage.setItem(USER_KEY, JSON.stringify(r.data))
        })
        .catch(() => {})
        .finally(() => setBooting(false))
    } else {
      setBooting(false)
    }
  }, [])

  useEffect(() => {
    const onLogout = () => {
      setUser(null)
      localStorage.removeItem(USER_KEY)
    }
    window.addEventListener('auth:logout', onLogout)
    return () => window.removeEventListener('auth:logout', onLogout)
  }, [])

  const login = useCallback(async (email, password) => {
    const { data } = await api.post('/auth/login', { email, password })
    setTokens({ accessToken: data.accessToken, refreshToken: data.refreshToken })
    setUser(data.user)
    localStorage.setItem(USER_KEY, JSON.stringify(data.user))
    return data.user
  }, [])

  const logout = useCallback(() => {
    setTokens(null)
    setUser(null)
    localStorage.removeItem(USER_KEY)
  }, [])

  const value = useMemo(() => ({ user, booting, login, logout }), [user, booting, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  return useContext(AuthContext)
}

/** True when the user's role is at least `role` in the hierarchy. */
export function hasRole(user, role) {
  if (!user) return false
  return (ROLE_RANK[user.role] ?? -1) >= (ROLE_RANK[role] ?? 99)
}
