import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import {
  LayoutDashboard,
  MonitorPlay,
  Image,
  ListVideo,
  LayoutPanelTop,
  CalendarClock,
  BarChart3,
  Users,
  FolderTree,
  Settings,
  LogOut,
  ChevronDown,
} from 'lucide-react'
import { useState } from 'react'
import clsx from 'clsx'
import { useAuth, hasRole } from '../auth/AuthContext'
import { Logo } from './Logo'
import { usePortalSocket } from '../ws/usePortalSocket'

// Items are appended as build phases land; keep in sync with routes in App.jsx
const NAV = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/screens', label: 'Screens', icon: MonitorPlay },
  { to: '/media', label: 'Media', icon: Image },
  { to: '/playlists', label: 'Playlists', icon: ListVideo },
  { to: '/layouts', label: 'Layouts', icon: LayoutPanelTop },
  { to: '/schedules', label: 'Schedules', icon: CalendarClock },
  { to: '/reports', label: 'Reports', icon: BarChart3 },
  { to: '/groups', label: 'Screen Groups', icon: FolderTree, minRole: 'ADMIN' },
  { to: '/users', label: 'Users', icon: Users, minRole: 'SUPER_ADMIN' },
  { to: '/settings', label: 'Settings', icon: Settings, minRole: 'ADMIN' },
]

const ROLE_LABEL = {
  SUPER_ADMIN: 'Super Admin',
  ADMIN: 'Admin',
  CONTENT_MANAGER: 'Content Manager',
  VIEWER: 'Viewer',
}

export default function Layout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const [menuOpen, setMenuOpen] = useState(false)
  usePortalSocket(true)

  return (
    <div className="flex min-h-screen">
      <aside className="fixed inset-y-0 left-0 w-60 bg-ink-800 text-white flex flex-col z-40">
        <div className="px-5 py-5 border-b border-white/10">
          <Logo dark />
          <p className="text-[11px] uppercase tracking-widest text-ink-300 mt-1">Digital Signage</p>
        </div>
        <nav className="flex-1 overflow-y-auto py-4 px-3 space-y-1">
          {NAV.filter((item) => !item.minRole || hasRole(user, item.minRole)).map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                clsx(
                  'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
                  isActive
                    ? 'bg-marigold text-ink-900'
                    : 'text-ink-100 hover:bg-white/10 hover:text-white',
                )
              }
            >
              <item.icon size={18} />
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="px-5 py-4 text-[11px] text-ink-300 border-t border-white/10">
          ScreenPilot · Digital Signage
        </div>
      </aside>

      <div className="flex-1 ml-60 flex flex-col min-w-0">
        <header className="sticky top-0 z-30 bg-cream/90 backdrop-blur border-b border-ink-100/70">
          <div className="flex items-center justify-end px-6 h-14 gap-4">
            <div className="relative">
              <button
                onClick={() => setMenuOpen((v) => !v)}
                className="flex items-center gap-2.5 rounded-lg px-2 py-1.5 hover:bg-ink-50"
              >
                <div className="h-8 w-8 rounded-full bg-ink-800 text-marigold flex items-center justify-center text-sm font-bold">
                  {user?.fullName?.[0]?.toUpperCase() || '?'}
                </div>
                <div className="text-left hidden sm:block">
                  <p className="text-sm font-semibold text-ink-800 leading-tight">{user?.fullName}</p>
                  <p className="text-[11px] text-ink-400 leading-tight">{ROLE_LABEL[user?.role] || user?.role}</p>
                </div>
                <ChevronDown size={15} className="text-ink-300" />
              </button>
              {menuOpen && (
                <>
                  <div className="fixed inset-0 z-10" onClick={() => setMenuOpen(false)} />
                  <div className="absolute right-0 mt-1 w-48 card p-1.5 z-20">
                    <div className="px-3 py-2 border-b border-ink-100 mb-1">
                      <p className="text-xs text-ink-400 truncate">{user?.email}</p>
                    </div>
                    <button
                      onClick={() => {
                        logout()
                        navigate('/login')
                      }}
                      className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-sm text-danger hover:bg-danger-100"
                    >
                      <LogOut size={15} /> Sign out
                    </button>
                  </div>
                </>
              )}
            </div>
          </div>
        </header>
        <main className="flex-1 px-6 py-6 max-w-[1500px] w-full mx-auto">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
