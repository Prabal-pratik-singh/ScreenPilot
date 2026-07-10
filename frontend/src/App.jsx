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

function RequireAuth({ children, minRole }) {
  const { user, booting } = useAuth()
  const location = useLocation()
  if (booting) {
    return (
      <div className="flex h-screen items-center justify-center">
        <Spinner className="h-8 w-8" />
      </div>
    )
  }
  if (!user) return <Navigate to="/login" state={{ from: location }} replace />
  if (minRole && !hasRole(user, minRole)) return <Navigate to="/" replace />
  return children
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/player" element={<PlayerPage />} />
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
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
