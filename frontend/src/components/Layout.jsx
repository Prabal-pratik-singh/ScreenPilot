// App shell for every logged-in portal page: fixed 256px dark sidebar with
// the brand lockup, gradient active nav pills, a weather + live IST clock
// widget and decorative glow; the content area carries an in-content topbar
// (welcome line on the dashboard, bell + profile chip everywhere) above the
// active route's <Outlet />. Below 768px the sidebar becomes an off-canvas
// drawer toggled by a hamburger. Also mounts usePortalSocket so live screen
// status updates flow while any portal page is open.
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
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
  Menu,
  X,
} from 'lucide-react'
import { useEffect, useState } from 'react'
import clsx from 'clsx'
import { useAuth, hasRole } from '../auth/AuthContext'
import { Logo } from './Logo'
import { BRAND } from '../config/brand'
import WeatherClockWidget from './WeatherClockWidget'
import NotificationsMenu from './NotificationsMenu'
import { usePortalSocket } from '../ws/usePortalSocket'

// Nav order per the design spec; minRole hides items the user may not open.
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

// The sidebar body — reused by the fixed rail (desktop) and the drawer (mobile).
function SidebarContent({ user, onNavigate }) {
  return (
    <div className="relative flex h-full flex-col overflow-hidden">
      {/* brand lockup */}
      <div className="px-5 pt-5 pb-4">
        <Logo withTagline />
      </div>

      {/* navigation */}
      <nav className="flex-1 overflow-y-auto px-3 py-2 space-y-1 relative z-10">
        {NAV.filter((item) => !item.minRole || hasRole(user, item.minRole)).map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.end}
            onClick={onNavigate}
            className={({ isActive }) =>
              clsx(
                'flex h-10 items-center gap-3 rounded-btn px-3 text-sm font-medium transition-all duration-150',
                isActive
                  ? 'bg-grad-primary text-white shadow-glow-primary'
                  : 'text-txt-secondary hover:bg-hover hover:text-txt-primary',
              )
            }
          >
            <item.icon size={18} />
            {item.label}
          </NavLink>
        ))}
      </nav>

      {/* weather + clock widget */}
      <div className="relative z-10 px-3 pb-4">
        <WeatherClockWidget />
      </div>

      {/* decorative: radial glow blob + faint dotted grid, non-interactive */}
      <div className="pointer-events-none absolute bottom-0 inset-x-0 h-72">
        <div className="absolute -bottom-24 -left-16 h-64 w-64 rounded-full bg-primary-600/25 blur-3xl" />
        <div className="absolute -bottom-16 -right-8 h-48 w-48 rounded-full bg-accent/20 blur-3xl" />
        <div
          className="absolute inset-0 opacity-[0.14]"
          style={{
            backgroundImage: 'radial-gradient(rgba(148,163,184,0.5) 1px, transparent 1px)',
            backgroundSize: '14px 14px',
          }}
        />
      </div>
    </div>
  )
}

// Renders the sidebar + topbar chrome around the active route's page.
export default function Layout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [menuOpen, setMenuOpen] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  usePortalSocket(true)

  // keep the browser tab title on brand
  useEffect(() => {
    document.title = BRAND.name
  }, [])

  const isDashboard = location.pathname === '/'
  const initials = (user?.fullName || '?')
    .split(' ')
    .map((w) => w[0])
    .slice(0, 2)
    .join('')
    .toUpperCase()

  return (
    <div className="flex min-h-screen bg-app">
      {/* fixed sidebar (desktop) */}
      <aside className="fixed inset-y-0 left-0 z-40 hidden w-64 border-r border-subtle bg-sidebar md:block">
        <SidebarContent user={user} />
      </aside>

      {/* off-canvas drawer (mobile) */}
      {drawerOpen && (
        <div className="fixed inset-0 z-50 md:hidden">
          <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={() => setDrawerOpen(false)} />
          <aside className="absolute inset-y-0 left-0 w-64 border-r border-subtle bg-sidebar animate-fade-up">
            <button
              onClick={() => setDrawerOpen(false)}
              className="absolute right-3 top-3 z-20 rounded-lg p-1.5 text-txt-muted hover:text-txt-primary hover:bg-hover"
              aria-label="Close menu"
            >
              <X size={18} />
            </button>
            <SidebarContent user={user} onNavigate={() => setDrawerOpen(false)} />
          </aside>
        </div>
      )}

      {/* main column */}
      <div className="flex-1 md:ml-64 flex flex-col min-w-0">
        <main key={location.pathname} className="flex-1 p-7 animate-fade-up">
          {/* in-content topbar */}
          <div className="mb-7 flex items-start justify-between gap-4">
            <div className="flex items-start gap-3 min-w-0">
              {/* hamburger, mobile only */}
              <button
                onClick={() => setDrawerOpen(true)}
                className="md:hidden mt-1 rounded-btn border border-subtle bg-hover p-2 text-txt-secondary hover:text-txt-primary"
                aria-label="Open menu"
              >
                <Menu size={18} />
              </button>
              {isDashboard ? (
                <div className="min-w-0">
                  <h1 className="text-[22px] font-semibold text-txt-primary tracking-tight truncate">
                    Welcome back, {user?.fullName?.split(' ')[0] || 'Admin'} 👋
                  </h1>
                  <p className="text-sm text-txt-secondary mt-0.5">
                    Here's what's happening with your digital signage network.
                  </p>
                </div>
              ) : (
                <div className="md:hidden">
                  <Logo size="sm" />
                </div>
              )}
            </div>

            <div className="flex items-center gap-3 shrink-0">
              {/* notifications — live alerts derived from screens/schedules */}
              <NotificationsMenu />

              {/* profile chip + menu */}
              <div className="relative">
                <button
                  onClick={() => setMenuOpen((v) => !v)}
                  className="flex items-center gap-2.5 rounded-btn px-1.5 py-1 transition-colors hover:bg-hover"
                >
                  <div className="h-9 w-9 rounded-full bg-grad-primary flex items-center justify-center text-[13px] font-semibold text-white">
                    {initials}
                  </div>
                  <div className="text-left hidden sm:block">
                    <p className="text-[13px] font-medium text-txt-primary leading-tight">{user?.fullName}</p>
                    <p className="text-xs text-txt-secondary leading-tight">{ROLE_LABEL[user?.role] || user?.role}</p>
                  </div>
                  <ChevronDown size={15} className="text-txt-muted" />
                </button>
                {menuOpen && (
                  <>
                    {/* invisible backdrop: clicking anywhere outside closes the menu
                        (z-40/z-50 stay above the Leaflet map's internal panes) */}
                    <div className="fixed inset-0 z-40" onClick={() => setMenuOpen(false)} />
                    <div className="absolute right-0 mt-1 w-48 card p-1.5 z-50 animate-pop-in shadow-2xl shadow-black/50">
                      <div className="px-3 py-2 border-b border-subtle mb-1">
                        <p className="text-xs text-txt-muted truncate">{user?.email}</p>
                      </div>
                      <button
                        onClick={() => {
                          logout()
                          navigate('/login')
                        }}
                        className="flex w-full items-center gap-2 rounded-btn px-3 py-2 text-sm text-danger hover:bg-danger/10 transition-colors"
                      >
                        <LogOut size={15} /> Sign out
                      </button>
                    </div>
                  </>
                )}
              </div>
            </div>
          </div>

          <Outlet />
        </main>
      </div>
    </div>
  )
}
