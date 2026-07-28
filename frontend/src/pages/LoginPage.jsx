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
    <div className="min-h-screen bg-gradient-to-br from-ink-800 via-ink-900 to-[#0A1120] flex items-center justify-center p-4 relative overflow-hidden">
      {/* drifting glow blobs give the navy backdrop depth without any images */}
      <div className="absolute -top-32 -right-32 h-96 w-96 rounded-full bg-marigold/15 blur-3xl animate-float" />
      <div className="absolute -bottom-40 -left-24 h-[28rem] w-[28rem] rounded-full bg-marigold/10 blur-3xl animate-float [animation-delay:2s]" />
      <div className="absolute top-1/3 left-1/2 h-64 w-64 rounded-full bg-ink-500/20 blur-3xl animate-float [animation-delay:4s]" />
      {/* faint grid texture over the whole backdrop */}
      <div
        className="absolute inset-0 opacity-[0.05]"
        style={{
          backgroundImage:
            'linear-gradient(rgba(255,255,255,0.5) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.5) 1px, transparent 1px)',
          backgroundSize: '44px 44px',
        }}
      />
      <div className="w-full max-w-md relative animate-fade-up">
        <div className="flex justify-center mb-8">
          <Logo dark size="lg" />
        </div>
        <div className="card p-8 shadow-2xl shadow-black/40 border-white/10 ring-1 ring-white/10">
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
