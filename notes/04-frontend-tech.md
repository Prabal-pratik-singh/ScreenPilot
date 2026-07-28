# Chapter 4 — Frontend Technologies (each one: what it is, why it's here, real example)

The frontend lives in `frontend/` and is declared in `frontend/package.json`. It is plain
JavaScript (no TypeScript) with a deliberately small toolchain: a dev server, a bundler, and
a handful of focused libraries.

---

## 4.1 React 18 — the UI library

**What it is:** a library for building user interfaces out of **components** — small
JavaScript functions that return a description of UI (written in **JSX**, an HTML-like
syntax). When a component's **state** changes, React re-renders *just that part* of the page.

```jsx
// A real-shaped example: a stat card like the ones on DashboardPage.jsx
function StatCard({ label, value }) {          // props = inputs from the parent
  return (
    <div className="card">
      <p className="text-sm">{label}</p>
      <p className="text-3xl font-semibold">{value}</p>
    </div>
  );
}
// used as:  <StatCard label="Online screens" value={9} />
```

**The two core ideas:**
- **Props** — inputs a component receives (like function arguments).
- **State** — data a component owns that can change (`useState`), triggering re-render.
  Example: the schedule wizard holds `const [step, setStep] = useState(0)` — clicking "Next"
  calls `setStep(1)` and React redraws the wizard at step 2.

**Why React for this project?**
- The portal is built from *repeating* widgets — cards, tables, modals, badges. Components
  turn each into a reusable function (`frontend/src/components/ui.jsx` is the shared kit).
- The player is a long-running state machine (pairing → downloading → playing). React's
  "UI = function of state" model means each state simply renders its screen, and transitions
  are just state changes.
- Enormous ecosystem — every library below plugs into React.

---

## 4.2 Vite — dev server + build tool

**What it is:** the tool that (a) runs a **dev server** with instant startup and **hot module
replacement** (save a file → the browser updates in place, without losing page state), and
(b) **bundles** the app for production (`npm run build` → optimized static files in `dist/`).

**Why Vite (over older Webpack setups)?** Speed. Vite serves source files as native browser
modules in dev, so starting the dev server takes under a second even as the app grows.

**Project-specific config worth understanding** (`frontend/vite.config.js`):
- `server.port: 5174` with **`strictPort: true`** — if 5174 is busy, *fail* instead of
  silently using 5175. Why so strict? The backend's CORS allow-list (chapter 7) names
  `http://localhost:5174` exactly; drifting to another port would break auth mysteriously.
- **No dev proxy** — in dev, the app calls the backend cross-origin at `http://localhost:8081`
  (from `.env.development`: `VITE_API_BASE_URL=http://localhost:8081`). In production the
  same variable is left **empty**, meaning "same origin", and nginx does the proxying.
  One code path (`api/client.js` checks `!== undefined`, not truthiness — so empty string is
  a valid, meaningful value) handles both worlds.

---

## 4.3 React Router (react-router-dom 6) — client-side navigation

**What it is:** the library that maps URLs to components *inside* the SPA — changing pages
without full page reloads (chapter 1, §SPA).

**The route table in `App.jsx` encodes the app's access model:**

```jsx
<Routes>
  <Route path="/login"  element={<LoginPage />} />        {/* public */}
  <Route path="/player" element={<PlayerPage />} />       {/* public — a TV has no login! */}

  <Route element={<RequireAuth><Layout /></RequireAuth>}> {/* everything below: logged-in,
                                                              wrapped in the sidebar shell */}
    <Route path="/" element={<DashboardPage />} />
    <Route path="/screens" element={<ScreensPage />} />
    <Route path="/screens/:id" element={<ScreenDetailPage />} />   {/* :id = URL parameter */}
    ...
    <Route element={<RequireAuth minRole="ADMIN"><Outlet/></RequireAuth>}>
      <Route path="/settings" element={<SettingsPage />} />
      <Route path="/groups"   element={<GroupsPage />} />
    </Route>
    <Route element={<RequireAuth minRole="SUPER_ADMIN"><Outlet/></RequireAuth>}>
      <Route path="/users" element={<UsersPage />} />
    </Route>
  </Route>

  <Route path="*" element={<Navigate to="/" replace />} />  {/* unknown URL → home */}
</Routes>
```

**Details that show care:**
- `RequireAuth` shows a spinner while the app re-validates the saved session (`/auth/me`) —
  preventing an ugly flash-redirect to /login on every refresh.
- When you're bounced to /login, the origin URL rides along (`state={{from: location}}`) so
  after logging in you land back where you wanted to go.
- Role gates in the router mirror the backend's rules — but remember (chapter 2): the UI
  hiding a page is *politeness*; the backend's `@PreAuthorize` is the *law*.

---

## 4.4 TanStack Query v5 — server-state management

**The problem it solves:** API data ("server state") is *shared, cacheable and refreshable* —
very different from local UI state. Without a manager, every page hand-rolls loading flags,
error handling, caching and refetching, and the same data gets re-fetched page after page.

**What it is:** a cache for API results, keyed by a **query key**. Components declare what
data they need; the library fetches, caches, dedupes and refreshes it.

```jsx
// ScreensPage.jsx (real pattern from the code)
const screens = useQuery({
  queryKey: ['screens', 'all'],                       // cache address
  queryFn: () => api.get('/screens').then(r => r.data),
});
// screens.isLoading → show skeletons; screens.data → render the table
```

**Mutations** (writes) then *invalidate* what they changed:

```jsx
const del = useMutation({
  mutationFn: (id) => api.delete(`/schedules/${id}`),
  onSuccess: () => queryClient.invalidateQueries({ queryKey: ['schedules'] }),
});  // → every component showing schedules silently refetches. No manual bookkeeping.
```

**Why it fits this app perfectly — the WebSocket trick:** the portal socket handler
(`ws/usePortalSocket.js`) receives `SCREEN_UPDATED` events and **patches the cache directly**:

```js
queryClient.setQueriesData({ queryKey: ['screens'] }, upsertById(event.screen));
```

Every table, map pin and detail panel showing that screen updates instantly — with **zero**
refetch requests. This is why the config sets `refetchOnWindowFocus: false` (the socket keeps
data fresh; focus-refetching would be wasted traffic) and `staleTime: 15000`.

---

## 4.5 axios — the HTTP client

**What it is:** a small library over the browser's `fetch` with two killer features:
**interceptors** (functions that run on every request/response) and nicer errors.

**In this project (`api/client.js`) the interceptors ARE the auth plumbing:**

```
request interceptor:                    response interceptor (the clever one):
every request gets                      on 401 (expired access token):
Authorization: Bearer <accessToken>       1. is a refresh ALREADY running?
                                             yes → wait for that same promise
                                             no  → POST /api/auth/refresh (once)
                                          2. store the new tokens (rotation)
                                          3. replay the original failed request
                                          4. if refresh itself fails → logout event
```

**The singleton-promise detail:** when a page fires 6 API calls and all hit 401
simultaneously, a naive implementation would call `/auth/refresh` 6 times (and 5 would fail,
because each refresh *rotates* the token). Here a module-level `refreshPromise` ensures
exactly **one** refresh happens and all 6 requests wait for it, then replay. Users never see
any of this — sessions just quietly continue.

**Decoupling detail:** on final auth failure, `client.js` can't call React code (it's not a
component), so it dispatches a DOM event `window.dispatchEvent(new Event('auth:logout'))`
which `AuthContext` listens for. A clean seam between plain JS and React.

(The **player** deliberately does *not* use this client — it has its own tiny `fetch`-based
`playerApi.js` with `X-Device-Token` auth, so device calls can never pick up user tokens or
trigger the user-refresh logic.)

---

## 4.6 @stomp/stompjs — the WebSocket client

**What it is:** the browser-side STOMP speaker (chapter 3 §3.7 defines STOMP). Connects to
`/ws`, subscribes to topics, auto-reconnects.

```js
// ws/usePortalSocket.js (portal)                    // player/PlayerPage.jsx (device)
new Client({                                          new Client({
  brokerURL: WS_URL,          // ws(s)://host/ws        brokerURL: WS_URL,
  reconnectDelay: 4000,       // retry every 4s         reconnectDelay: 5000,
});                                                     connectHeaders: {
// subscribes: /topic/portal/screens                      'x-device-token': deviceToken },
                                                      }); // subscribes: /topic/screen/<id>
```

**Why the raw-WebSocket flavor (no SockJS shim):** every browser this project targets
supports native WebSocket; skipping SockJS removes a dependency. The backend still exposes
both flavors of `/ws` for maximum client compatibility (e.g. the Android WebView).

---

## 4.7 Tailwind CSS — styling

**What it is:** a CSS framework of tiny single-purpose **utility classes** composed directly
in the markup, instead of inventing class names and separate stylesheets:

```jsx
<button className="rounded-lg bg-marigold-500 px-4 py-2 font-medium text-ink-900
                   hover:bg-marigold-400 disabled:opacity-50">
  Publish
</button>
```

**Why Tailwind here?**
- **Co-location** — a component's look lives in the component; no hunting through CSS files,
  no dead styles accumulating.
- **A design system in one file** — `tailwind.config.js` defines the brand palette once:
  `marigold` (#F6A821), `ink` navy (#16233F), `cream`, plus semantic success/danger/warning
  shades. Every page, chart, map legend — even the *backend's* Excel/PDF exports — reuse
  these exact colors. One brand, four output formats.
- Repeated patterns are extracted **once** with `@layer components` in `index.css`
  (`.btn`, `.card`, `.input`), so markup stays readable.

Supporting cast: **PostCSS** (the pipeline Tailwind runs in) and **autoprefixer** (adds
vendor prefixes for older browsers — think TV WebViews).

---

## 4.8 @dnd-kit — drag and drop (the playlist builder)

**What it is:** a modern React drag-and-drop toolkit (successor to older libraries like
react-beautiful-dnd), used **only** in `PlaylistBuilderPage.jsx`.

**What it powers:** dragging media cards from the library pane into the playlist, and
re-ordering playlist rows — both handled by one `onDragEnd` (drag ids prefixed `lib:` mean
"insert"; otherwise "reorder" via `arrayMove`).

**Two thoughtful details:**
- `PointerSensor` with `activationConstraint: { distance: 6 }` — a drag only starts after the
  pointer moves 6px, so *clicking* buttons inside a draggable card still works.
- A `DragOverlay` renders the floating "ghost" card while dragging.

**Why NOT dnd-kit in the layout designer?** `LayoutDesignerPage.jsx` needs free 2-D movement
+ **resizing** + snap-to-grid — that's not list sorting. It uses raw pointer events and
percentage math instead: pointer deltas ÷ canvas size × 100, snapped to a 24-column grid.
Right tool for each job; no library contortions.

---

## 4.9 Leaflet (+ react-leaflet, react-leaflet-cluster) — the live map

**What it is:** the standard open-source interactive-map library, drawing free
**OpenStreetMap** tiles — no API key, no billing (vs Google Maps).

**In this project** (`components/ScreensMap.jsx`, shown on the dashboard):
- Each screen with coordinates becomes a colored dot — a custom `L.divIcon` (a tiny piece of
  HTML) rather than the default image marker. Green = online, red = offline. (Bonus: HTML
  markers sidestep Leaflet's classic broken-marker-icon problem with bundlers.)
- **Clustering** groups nearby markers at low zoom into a count badge; the badge is green if
  the whole cluster is online, red if all offline, **amber if mixed** — you can spot a
  problem city from the whole-country view.
- Popups link straight to the screen's detail page.

---

## 4.10 Recharts — the report charts

**What it is:** a React charting library — you compose charts from components
(`<BarChart>`, `<XAxis>`, `<Tooltip>`) instead of imperative canvas drawing.

**In this project** (`ReportsPage.jsx`):
- Proof-of-play: a **bar chart** of plays per day (the report pre-fills empty days so the
  chart has no gaps).
- Uptime: a **line chart with `type="stepAfter"`** — a deliberate correctness choice: a
  screen is either online or offline; a smooth curve between points would *lie* about what
  happened between samples. Steps tell the truth.
- Chart colors are darker variants of the brand palette (`#A96D07`, `#3D5378`) because the
  raw marigold on a white card fails the **3:1 contrast** accessibility ratio.

---

## 4.11 The small-but-worth-knowing dependencies

| Package | What it is | Why it's here |
|---|---|---|
| **lucide-react** | Icon set as React components | One consistent, tree-shakeable icon language across nav, buttons, empty states |
| **clsx** | Tiny helper to join class names conditionally | Things like `clsx('badge', pct < 80 && 'text-danger-700')` — cleaner than string concatenation |
| **@fontsource/inter** | The Inter font as an npm package | Fonts are **self-hosted in the bundle — no Google Fonts CDN** — because players may run on LAN-only/offline networks where a CDN request would hang or fail |
| **date-fns** | Date utility library | **Declared but actually unused** — all date work is hand-rolled with the browser's built-in `Intl.DateTimeFormat`, because everything must render in IST regardless of the device's clock settings (see below). A leftover, removable dependency — an honest imperfection. |

---

## 4.12 Browser platform features (no library needed)

The player especially leans on what browsers already provide:

| Feature | What it is | Used for |
|---|---|---|
| **localStorage** | Tiny key-value store (strings only, ~5–10 MB), synchronous, survives restarts | Login tokens (`screenpilot.tokens`), cached user, the TV's device identity, the TV's last good config |
| **IndexedDB** | A real in-browser database for large binary data (hundreds of MB), asynchronous | The TV's **media cache** (video/image blobs) and the offline **proof-of-play queue** |
| **`URL.createObjectURL(blob)`** | Turns an in-memory blob into a playable URL | `<video src=...>` plays straight from IndexedDB; URLs are revoked on eviction so a 24/7 device doesn't leak memory |
| **`Intl.DateTimeFormat`** | Built-in timezone-aware date formatting | *Everything* time-related is rendered with `timeZone: 'Asia/Kolkata'` — a TV with a wrong timezone setting still plays the 6 PM banner at 6 PM IST |
| **`navigator.storage.estimate()`** | Asks the browser "how much disk am I using?" | The storage bar on the screen-detail page, reported in heartbeats |
| **`navigator.onLine` + online event** | "Is there a network?" | Skip external URLs while offline; flush logs & refresh config the moment connectivity returns |

**localStorage vs IndexedDB in one line:** localStorage is a sticky note (tiny, instant,
strings); IndexedDB is a filing cabinet (big, asynchronous, blobs). The player uses the note
for identity/config and the cabinet for media/logs.

---

**Next:** [Chapter 5 — The player & the Android TV app →](05-player-and-android.md)
