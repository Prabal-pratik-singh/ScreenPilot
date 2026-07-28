// Route table for the whole app (React Router). Two routes are public:
// /login and /player (the TV player must run without a portal login — screens
// authenticate with their own device token instead). Everything else sits
// behind RequireAuth inside the shared Layout shell, with some routes also
// demanding a minimum role (Settings/Groups = ADMIN, Users = SUPER_ADMIN).
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useAuth, hasRole } from './auth/AuthContext'
import Layout from './components/Layout'
import LoginPage from './pages/LoginPage'
import DashboardPage from './pages/DashboardPage'
import ScreensPage from './pages/ScreensPage'
import ScreenDetailPage from './pages/ScreenDetailPage'
import UsersPage from './pages/UsersPage'
import GroupsPage from './pages/GroupsPage'
import MediaPage from './pages/MediaPage'
import PlaylistsPage from './pages/PlaylistsPage'
import PlaylistBuilderPage from './pages/PlaylistBuilderPage'
import SchedulesPage from './pages/SchedulesPage'
import ScheduleWizardPage from './pages/ScheduleWizardPage'
import LayoutsPage from './pages/LayoutsPage'
import LayoutDesignerPage from './pages/LayoutDesignerPage'
import ReportsPage from './pages/ReportsPage'
import SettingsPage from './pages/SettingsPage'
import PlayerPage from './player/PlayerPage'
import { Spinner } from './components/ui'

// Route guard: waits for the session check (booting), redirects anonymous
// users to /login, and bounces under-privileged users back to the dashboard.
function RequireAuth({ children, minRole }) {
  const { user, booting } = useAuth()
  // The URL the visitor was trying to open — captured so the login page can
  // send them back there afterwards.
  const location = useLocation()
  // While AuthContext is still verifying the stored session we simply don't
  // know yet whether this visitor is logged in. Rendering a spinner instead of
  // deciding avoids the flash where a logged-in user gets bounced to /login
  // for a split second and then straight back.
  if (booting) {
    return (
      <div className="flex h-screen items-center justify-center">
        <Spinner className="h-8 w-8" />
      </div>
    )
  }
  // Not logged in → off to /login. The attempted URL rides along in router
  // state so LoginPage can bounce the user back after a successful login.
  // `replace` swaps the current history entry instead of pushing a new one, so
  // the Back button can't return to the guarded page and re-trigger this.
  if (!user) return <Navigate to="/login" state={{ from: location }} replace />
  // Logged in but ranked below minRole → quietly land on the dashboard.
  if (minRole && !hasRole(user, minRole)) return <Navigate to="/" replace />
  // All checks passed — render the protected page.
  return children
}

// The route table itself; it renders inside BrowserRouter (see main.jsx).
export default function App() {
  return (
    <Routes>
      {/* ---- Public world: reachable without any login ---- */}
      <Route path="/login" element={<LoginPage />} />
      {/* The TV player is public on purpose: a screen authenticates with its
          own device token obtained during pairing, never with a user session. */}
      <Route path="/player" element={<PlayerPage />} />
      {/* ---- Portal world: everything below requires a login ---- */}
      {/* This is a pathless "layout route": it has no path of its own, it just
          wraps RequireAuth + Layout around all the child routes below. Each
          child renders inside Layout's <Outlet />, so ONE guard and ONE shell
          (sidebar, header) cover every portal page at once. */}
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route path="/" element={<DashboardPage />} />
        <Route path="/screens" element={<ScreensPage />} />
        <Route path="/screens/:id" element={<ScreenDetailPage />} />
        <Route path="/media" element={<MediaPage />} />
        <Route path="/playlists" element={<PlaylistsPage />} />
        <Route path="/playlists/:id" element={<PlaylistBuilderPage />} />
        <Route path="/schedules" element={<SchedulesPage />} />
        <Route path="/schedules/new" element={<ScheduleWizardPage />} />
        <Route path="/schedules/:id/edit" element={<ScheduleWizardPage />} />
        <Route path="/layouts" element={<LayoutsPage />} />
        <Route path="/layouts/:id" element={<LayoutDesignerPage />} />
        <Route path="/reports" element={<ReportsPage />} />
        {/* The three routes below add a SECOND, stricter RequireAuth on top of
            the outer login check: Settings and Groups demand ADMIN, Users
            demands SUPER_ADMIN. Lower roles get redirected to the dashboard. */}
        <Route
          path="/settings"
          element={
            <RequireAuth minRole="ADMIN">
              <SettingsPage />
            </RequireAuth>
          }
        />
        <Route
          path="/groups"
          element={
            <RequireAuth minRole="ADMIN">
              <GroupsPage />
            </RequireAuth>
          }
        />
        <Route
          path="/users"
          element={
            <RequireAuth minRole="SUPER_ADMIN">
              <UsersPage />
            </RequireAuth>
          }
        />
      </Route>
      {/* Catch-all: any unknown URL lands on the dashboard. Anonymous visitors
          are then forwarded from there to /login by RequireAuth. */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
