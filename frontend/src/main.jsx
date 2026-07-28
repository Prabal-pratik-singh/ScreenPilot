// App entry point: mounts React into #root and stacks the global providers —
// TanStack Query (server-state cache), AuthProvider (login session) and
// BrowserRouter (client-side routing) — around the route table in App.jsx.
import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
// The Inter font is self-hosted via @fontsource (each import bundles one font
// weight into our own build) instead of loading from a fonts CDN. Signage
// players often run on TVs with little or no internet beyond the portal
// itself, so every asset must come from our own server to render offline.
import '@fontsource/inter/400.css'
import '@fontsource/inter/500.css'
import '@fontsource/inter/600.css'
import '@fontsource/inter/700.css'
import './index.css'
import App from './App'
import { AuthProvider } from './auth/AuthContext'

// Query cache defaults: retry a failed request once, treat data as fresh for
// 15s, and don't refetch just because the browser tab regained focus.
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // One retry smooths over a transient network blip; more retries would
      // only delay the error message the user needs to see.
      retry: 1,
      // Data fetched less than 15s ago is served straight from the cache with
      // no new HTTP request — makes hopping between pages feel instant.
      staleTime: 15000,
      // Deliberately off: the WebSocket (usePortalSocket) already pushes screen
      // changes into this cache in real time, so refetching every time the tab
      // regains focus would just duplicate traffic for already-fresh data.
      refetchOnWindowFocus: false,
    },
  },
})

// createRoot attaches the React tree to the <div id="root"> in index.html.
// Provider NESTING ORDER matters — a hook can only reach a provider mounted
// above it in the tree:
// - QueryClientProvider outermost: everything below (auth, router, pages, the
//   WebSocket hook) may read/write the shared server-state cache.
// - AuthProvider above the router: the session lives outside routing, so it
//   survives navigation and the RequireAuth guards inside App can call useAuth().
// - BrowserRouter innermost wrapper: App's <Routes> plus every useLocation/
//   useNavigate call need it.
// React.StrictMode is a dev-only safety net (it double-runs renders/effects to
// expose unsafe side effects); it adds nothing in the production build.
ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  </React.StrictMode>,
)
