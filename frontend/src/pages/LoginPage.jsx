// Sign-in page for the portal (public route /login). Submits email/password
// through AuthContext's login(), then redirects back to whatever protected
// page the user originally tried to open (kept in router location state).
import { useState } from 'react'
import { useNavigate, useLocation, Navigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { Logo } from '../components/Logo'
import { Spinner } from '../components/ui'
import { errorMessage } from '../api/client'

export default function LoginPage() {
  const { user, login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  // Already signed in? Skip the form entirely.
  if (user) return <Navigate to={location.state?.from?.pathname || '/'} replace />

  // Form submit handler: attempts login, shows the API error message on failure.
  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(email.trim(), password)
      navigate(location.state?.from?.pathname || '/', { replace: true })
    } catch (err) {
      setError(errorMessage(err, 'Login failed'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="min-h-screen bg-ink-800 flex items-center justify-center p-4 relative overflow-hidden">
      <div className="absolute -top-32 -right-32 h-96 w-96 rounded-full bg-marigold/10" />
      <div className="absolute -bottom-40 -left-24 h-[28rem] w-[28rem] rounded-full bg-marigold/5" />
      <div className="w-full max-w-md">
        <div className="flex justify-center mb-8">
          <Logo dark size="lg" />
        </div>
        <div className="card p-8">
          <h1 className="text-xl font-bold text-ink-800">Sign in to the portal</h1>
          <p className="text-sm text-ink-400 mt-1 mb-6">Manage screens, content and schedules across the network.</p>
          <form onSubmit={submit} className="space-y-4">
            <div>
              <label className="label">Email</label>
              <input
                type="email"
                required
                autoFocus
                className="input"
                placeholder="you@screenpilot.in"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            </div>
            <div>
              <label className="label">Password</label>
              <input
                type="password"
                required
                className="input"
                placeholder="••••••••"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>
            {error && (
              <div className="rounded-lg bg-danger-100 text-danger-700 text-sm px-3 py-2">{error}</div>
            )}
            <button type="submit" disabled={busy} className="btn-primary w-full">
              {busy ? <Spinner className="h-4 w-4" /> : 'Sign in'}
            </button>
          </form>
        </div>
        <p className="text-center text-xs text-ink-300 mt-6">
          ScreenPilot · Your screens on autopilot
        </p>
      </div>
    </div>
  )
}
