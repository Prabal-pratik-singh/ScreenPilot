# Chapter 2 — The Architecture (with diagrams)

> "Architecture" just means: what are the big pieces, what does each one own, and how do they
> talk to each other. This chapter is the map; chapters 3–9 zoom into each region.

---

## 2.1 The style in one sentence

ScreenPilot is a **3-tier client–server system** (clients → one backend → one database),
where the backend is a **layered monolith**, real-time updates ride a **publish/subscribe
WebSocket channel**, and the player client is **offline-first**.

Each bold term gets its own section below.

---

## 2.2 The journey of one request (tier by tier)

Let's follow a single click — you open the **Screens** page — through every layer it touches.
This is the most useful diagram in the guide; almost every feature works exactly like this:

```
 YOU click "Screens" in the sidebar
   │
   ▼
 [React]        ScreensPage.jsx mounts; TanStack Query sees no cached data for
                key ['screens','all'] → asks axios to fetch
   │
   ▼
 [axios]        adds header  Authorization: Bearer <your JWT>
                GET http://localhost:8090/api/screens
   │
   ▼
 [nginx]        sees the path starts with /api  → forwards to backend container
                (adds X-Forwarded-For so the backend knows your real IP)
   │
   ▼
 [Filters]      Spring Security filter chain (runs BEFORE any controller):
                 1. RateLimitFilter   — is this IP hammering login/pairing? (not this URL → pass)
                 2. JwtAuthFilter     — validates the JWT signature + expiry,
                                        loads your user + role + allowed groups
                 3. Authorization     — /api/screens GET requires role ≥ VIEWER → OK
   │
   ▼
 [Controller]   ScreenController.list()          ← thin: no logic, just delegates
   │
   ▼
 [Service]      ScreenService.accessibleScreens()  ← THE business rule lives here:
                filters screens to the groups YOUR account may see
   │
   ▼
 [Repository]   ScreenRepository (Spring Data JPA) → Hibernate generates SQL
   │
   ▼
 [PostgreSQL]   SELECT * FROM screens ... ;  rows come back
   │
   ▼
 [DTO mapping]  ScreenMapper.toDto(...) converts each entity to a ScreenResponse record —
                adds computed fields (offlineSeconds), signs the thumbnail URL,
                and leaves out anything secret (device-token hash)
   │
   ▼
 [Jackson]      Java records → JSON array
   │
   ▼
 [React]        TanStack Query caches the result under ['screens','all'];
                the table renders; the map places pins
```

**The key mental model:** *requests travel down through layers, data travels back up*, and
each layer has exactly one job. Security happens **before** any business code runs.

---

## 2.3 The backend's layered architecture (folder = layer)

Every folder under `src/main/java/com/screenpilot/signage/` is one layer or one concern:

```
                    HTTP request
                        │
   ┌────────────────────▼─────────────────────┐
   │ security/   filters run first            │  JwtAuthFilter, DeviceTokenFilter,
   │             (who are you? allowed?)      │  RateLimitFilter, UrlSigner, TokenHasher
   └────────────────────┬─────────────────────┘
   ┌────────────────────▼─────────────────────┐
   │ controller/ CONTROLLERS — receive HTTP,  │  ScreenController, MediaController,
   │             check @PreAuthorize, call    │  ScheduleController, PlayerController,
   │             one service, return DTOs.    │  ReportController, AuthController ...
   │             NO business logic here.      │
   └────────────────────┬─────────────────────┘
   ┌────────────────────▼─────────────────────┐
   │ service/    BUSINESS LOGIC + transactions│  ScreenService, ScheduleService,
   │             validation, group scoping,   │  HeartbeatService, ReportService,
   │             the actual rules             │  PairingService, ExportService ...
   └──────┬──────────────┬──────────────┬─────┘
          │              │              │
   ┌──────▼─────┐ ┌──────▼──────┐ ┌─────▼─────────┐
   │ repo/      │ │ ws/         │ │ storage/ media/│
   │ REPOSITORIES│ │ WebSocket   │ │ files +       │
   │ DB queries  │ │ push        │ │ thumbnails    │
   └──────┬─────┘ └─────────────┘ └───────────────┘
   ┌──────▼─────────────────────────────────────┐
   │ domain/     ENTITIES — Java classes mapped │  Screen, Schedule, Playlist,
   │             1:1 to database tables         │  MediaAsset, LayoutZone ...
   └────────────────────────────────────────────┘

   Cross-cutting (used by all layers):
   dto/     the JSON shapes the API speaks (Java records)
   error/   ApiException + GlobalExceptionHandler (one error format for everything)
   config/  Spring wiring: SecurityConfig, WebSocketConfig, AppProperties, WebConfig
   seed/    demo data inserted on first start
   integrations/  future content sources (Canva, Drive...) behind an interface
```

### Why layer it? (the payoff, with a concrete example)

The rule *"which screens may this user see?"* exists in exactly **one** method:
`ScreenService.accessibleScreens()`. The dashboard, the map, the reports, and the schedule
wizard all call it. When you change the rule, you change one method — it is *impossible* for
the reports page to disagree with the dashboard about permissions. Without layers, that rule
would be copy-pasted into every controller and inevitably drift.

Two supporting patterns:

- **DTOs (Data Transfer Objects)** — the API never returns database entities directly.
  Entities contain things that must never leak (password hashes, device-token hashes) and
  their shape follows the DB, not the client's needs. Instead, Java **records** in `dto/`
  define exactly what the JSON looks like. Bonus: DTO factory methods are where **signed
  media URLs** get attached (e.g. `MediaResponse.from(asset)` mints a 12-hour signed link).
- **One error contract** — `GlobalExceptionHandler` converts *every* failure (validation,
  bad login, forbidden, oversized upload, unexpected crash) into the same JSON:
  `{timestamp, status, error, message, path, fieldErrors?}`. The frontend therefore has one
  tiny error-parsing helper (`errorMessage()` in `client.js`) that works for every endpoint.

---

## 2.4 The frontend's structure

```
frontend/src/
├── main.jsx            entry — wires the providers (order matters):
│                       QueryClientProvider > AuthProvider > BrowserRouter > App
├── App.jsx             the route table:
│                         /login              → LoginPage        (public)
│                         /player             → PlayerPage       (public, no sidebar —
│                         │                      a TV has no user account!)
│                         everything else     → RequireAuth + Layout (sidebar shell):
│                         /                   → DashboardPage
│                         /screens, /screens/:id, /media, /playlists/:id,
│                         /layouts/:id, /schedules, /schedules/new, /reports
│                         /settings, /groups  → ADMIN only
│                         /users              → SUPER_ADMIN only
├── auth/AuthContext.jsx  who is logged in; role checks (hasRole)
├── api/client.js         axios instance + JWT header + auto-refresh on 401
├── ws/usePortalSocket.js  the portal's live-update subscription
├── components/            Layout (sidebar+header), ScreensMap, ui.jsx (buttons, modals...)
├── pages/                 one file per portal page
├── player/                the ENTIRE player app (own API client, own storage, no axios)
│     PlayerPage.jsx        orchestrator/state machine
│     PlaylistPlayer.jsx    double-buffered playback loop
│     LayoutRenderer.jsx    multi-zone rendering
│     scheduleEngine.js     "what should play right now?" (IST)
│     downloadManager.js    IndexedDB media cache + download queue
│     logQueue.js           offline-safe proof-of-play queue
│     db.js                 minimal IndexedDB wrapper
│     playerApi.js          fetch-based API client with device-token auth
└── lib/                   formatting helpers (IST times, bytes, durations)
```

**A deliberate wall:** the player folder does **not** import the portal's axios client or
AuthContext. The player authenticates with a **device token**, not a user JWT — mixing the
two auth worlds would risk a TV accidentally sending a user's credentials or triggering the
user-token refresh logic. Two clients, two auth schemes, zero shared state.

---

## 2.5 Real-time design: publish/subscribe topics

REST can't push. For live updates the backend runs a **STOMP broker** (chapter 3 defines
STOMP) with named channels — "**topics**" — that clients subscribe to:

```
                        ┌────────────────────────────┐
                        │   Spring STOMP broker      │
                        │   (in-memory, /ws endpoint)│
                        └────────────────────────────┘
   SUBSCRIBES                    │      ▲                     SUBSCRIBES
                                 │      │
 Portal (every open tab)         │      │ heartbeats     Player of screen 42
   /topic/portal/screens ◀───────┘      │ (/app/player/    /topic/screen/42
   · SCREEN_UPDATED {screen}            │  heartbeat)      · SCHEDULES_UPDATED
   · SCREEN_REMOVED {id}                │                  · PLAYLIST_UPDATED {playlistId}
                                        │                  · LAYOUT_UPDATED  {layoutId}
                                        │                  · COMMAND {RELOAD|CLEAR_CACHE|
                                        │                             SCREENSHOT, commandId}
                                 Player sends
```

- **One broadcast topic for the portal** (`/topic/portal/screens`): every open dashboard tab
  hears about every screen change — that's how the map dot flips red *live*.
- **One private topic per screen** (`/topic/screen/{id}`): the backend can poke exactly one TV.
- **Heartbeats flow inbound** on the same socket (with HTTP POST as fallback).

### The push + pull hybrid (an important design decision)

Notice the pushed messages are tiny — `{type: "SCHEDULES_UPDATED"}` carries **no data**.
The player reacts by *pulling* the full config over REST (`GET /api/player/config`).

**Why not push the whole new schedule?** Because pushes can be missed (TV was rebooting,
Wi-Fi blinked). If pushes carried the data, a missed push = a permanently wrong screen. Here,
a missed push costs at most 5 minutes: the player re-pulls its config on a timer anyway, on
every reconnect, and on the browser's `online` event. **Push = speed. Pull = truth.**
The system *converges* to correct even when messages are lost — this property is called
**eventual consistency**, achieved with kindergarten-simple code.

---

## 2.6 Offline-first: the player's philosophy

Most web apps die without internet. This player is designed backwards from the question:
*"the shop's Wi-Fi died at 9 AM — what happens on the TV at 6 PM?"* Answer: the Evening
Offers still start at 18:00 sharp. How:

```
   what the player has locally          →  what it can do with no server at all
   ─────────────────────────────────      ─────────────────────────────────────
   media files in IndexedDB            →  keep playing every loop
   last good config in localStorage    →  survive a full reboot while offline
   schedule rules + IST clock logic    →  switch content at the right time
   proof-of-play queue in IndexedDB    →  keep collecting billing evidence
   device token in localStorage        →  reconnect without re-pairing

   when the network returns            →  sync logs, refresh config, heartbeat again
```

The server ships **rules**, not commands ("play playlist A all day, playlist B 18:00–22:00"),
and the player applies them locally. The server is the *librarian*, not the *puppeteer*.

---

## 2.7 Where state lives (a map of every storage location)

| Data | Lives in | Why there |
|---|---|---|
| Users, screens, playlists, schedules, logs… | **PostgreSQL** | The single source of truth; relational; transactional |
| Media files + thumbnails | **`./uploads` folder** (behind `StorageService`) | Files don't belong in a DB; interface allows S3 swap later |
| Your login session | **JWT in browser localStorage** | Stateless backend — the token *is* the session |
| Portal's fetched data | **TanStack Query in-memory cache** | Fast page switches; patched live by WebSocket events |
| TV's identity (device token) | **Player localStorage** | Survives reboots; deleting the screen in the portal un-pairs it |
| TV's media cache | **Player IndexedDB** | Big binary blobs; survives reboots; enables offline playback |
| TV's pending proof-of-play | **Player IndexedDB** | Must survive offline periods and reboots until acknowledged |
| Android app's server address | **SharedPreferences** | The only thing the native shell remembers |

---

## 2.8 Honest constraints of this architecture (and why they're OK)

Three components are **in-memory and single-node** by design:

1. the WebSocket broker (Spring's *simple broker*),
2. the rate limiter (a `ConcurrentHashMap` of request timestamps),
3. the scheduled jobs (offline sweeper) — they'd run once *per instance* if you ran two.

Running **two** backend instances would break all three (a player connected to instance A
would never hear pushes triggered on instance B). For a fleet of hundreds of screens, one
modest backend is plenty — and each piece has a well-known upgrade path (external broker like
RabbitMQ, Redis-based rate limiting, leader-elected jobs) *when* the scale demands it.
The lesson: **the architecture states its assumptions instead of pretending to be
infinitely scalable.**

---

**Next:** [Chapter 3 — Backend technologies, each defined with examples →](03-backend-tech.md)
