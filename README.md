# ScreenPilot — Digital Signage Platform

**Your screens on autopilot.** ScreenPilot is a full digital-signage platform for running content
across a fleet of store/office screens. Two parts, one repo:

- **Portal (CMS)** — central dashboard to manage screens, media, playlists, multi-zone layouts,
  schedules, users and reports.
- **Player** — a fullscreen web app (`/player`) that runs on Android TV boxes / any kiosk-mode
  browser: pairs with a 6-character code, caches media in IndexedDB for offline playback, evaluates
  IST schedules locally, and reports heartbeats + proof-of-play in real time.

**Stack:** Spring Boot 3.3 (Java 17) · Spring Security (JWT) · Spring Data JPA · WebSocket/STOMP ·
PostgreSQL + Flyway · React 18 + Vite · TanStack Query · Tailwind CSS · @dnd-kit · Leaflet ·
Recharts · Apache POI · openhtmltopdf.

---

## Quick start — Docker, one command

The only prerequisite is Docker. From the repo root:

```bash
docker compose up -d --build
```

That builds and starts all three services:

| Service | Container | URL / port |
|---|---|---|
| Portal + Player (nginx) | `screenpilot-frontend` | **http://localhost:8090** (player at `/player`) |
| Backend API | `screenpilot-backend` | http://localhost:8081 (also proxied at `/api` on 8090) |
| PostgreSQL 16 | `signage-postgres` | localhost:5433 |

The frontend container serves the built app and reverse-proxies `/api` and `/ws` to the backend,
so everything is same-origin on port 8090 — one URL for the portal and every player. The backend
image includes **ffmpeg**, so video thumbnails and duration probing work out of the box. Media
uploads persist in the host `./uploads` folder (shared with local-dev runs), the database in the
`signage_pgdata` volume.

First start seeds the demo data — log in with the credentials below. Stop with
`docker compose down` (data survives; add `-v` to wipe it).

---

## Local development (hot reload)

For working on the code, run only the database in Docker and the apps natively.

### Prerequisites

| Tool | Version |
|---|---|
| Java (JDK) | 17+ (tested on 25) |
| Node.js | 18+ (tested on 24) |
| Docker | any recent (for PostgreSQL) |
| ffmpeg *(optional)* | video thumbnails + duration probing; typed placeholders are used without it |

Ports used: **8081** (backend), **5174** (frontend dev), **5433** (PostgreSQL).

## 1 — Start PostgreSQL

```bash
docker compose up -d postgres
```

Starts `signage-postgres` on **localhost:5433** (db/user/password: `signage`). Flyway migrates the
schema on backend startup (`V1`–`V6`).

## 2 — Run the backend

```bash
./mvnw spring-boot:run          # Windows: .\mvnw.cmd spring-boot:run
```

First run seeds demo data and prints the credentials:

| Login | Password | Role |
|---|---|---|
| `admin@screenpilot.in` | `ScreenPilot@123` | SUPER_ADMIN |
| `content.ranchi@screenpilot.in` | `Content@123` | CONTENT_MANAGER (Ranchi group only) |
| `viewer@screenpilot.in` | `Viewer@123` | VIEWER (read-only) |

Also seeded: 3 screen groups (Ranchi / Patna / Kolkata), 12 demo screens with coordinates across
JH/BR/WB, 4 branded sample images (plus a demo video when ffmpeg is present).

## 3 — Run the frontend

```bash
cd frontend
npm install
npm run dev
```

Portal: **http://localhost:5174** — API base comes from `frontend/.env.development`
(`VITE_API_BASE_URL=http://localhost:8081`), nothing is hardcoded.

## 4 — Demo the player (fake screens)

Works the same in both modes — use **http://localhost:8090** (Docker) or
**http://localhost:5174** (dev) as the base URL.

1. Open two extra browser tabs (or windows) at **`<base>/player`** — each shows a big
   6-character pairing code. These are your "store screens".
2. In the portal: **Screens → Add screen**, type a tab's code, fill the details (pick a group,
   city, coordinates) → **Pair screen**. The player tab switches to the branded idle screen by
   itself and starts heartbeating — watch it go **green/online live** on the Dashboard and map.
3. Upload content under **Media** (drag & drop mp4/jpg/png/webp/pdf — per-file progress bars,
   auto thumbnails).
4. Build a loop under **Playlists** (drag assets in, reorder with drag-and-drop, set per-image
   durations, add a YouTube link or live URL).
5. **Schedules → New schedule**: pick the playlist → tick your paired screens in the
   State→City→Store tree → choose "All day" or a time window (IST) → Publish. The players receive
   the push over WebSocket, download media into IndexedDB (watch per-creative download status on
   the screen detail page), and start the loop with seamless double-buffered transitions.
6. Live update: edit the playlist while it plays and Save — every screen playing it hot-swaps
   without going black. A timed window beats the all-day loop during its hours.
7. Offline-first: kill the backend (or disconnect) — the player keeps looping cached content and
   queues proof-of-play logs; on reconnect it syncs them. The dashboard marks it offline after 90s
   without a heartbeat ("Offline for 3h 20m").
8. Try a multi-zone **Layout** (e.g. the L-shape preset: main media + clock sidebar + scrolling
   ticker), assign playlists per zone, schedule it like a playlist.
9. **Screen detail → Remote control**: reload the player, clear its cache & re-download, or
   request a screenshot of what it is showing right now.
10. **Reports**: proof-of-play (per creative × screen: plays, seconds on screen, first/last) and
    per-day uptime with a red-flag list — both exportable to branded **Excel and PDF**.

> Player tip: it is a normal browser tab pretending to be an Android box. Fullscreen it (F11) for
> the kiosk feel. Pairing state lives in `localStorage`, cached media in IndexedDB — deleting the
> screen in the portal un-pairs the device on its next heartbeat.

## Android TV player app

`android-tv/` contains a native kiosk app (Kotlin, zero external dependencies, ~1 MB APK) that
wraps the web player for real TV fleets:

- **Auto-start on boot** (`BOOT_COMPLETED` receiver) — plug the box in and signage plays
- **Fullscreen kiosk WebView** — screen never sleeps, muted autoplay allowed, BACK disabled
  (press BACK 5× to open the server-address setup screen)
- **Self-healing** — recreates itself after WebView renderer crashes; auto-retries with backoff
  when the network/server drops and reloads the moment connectivity returns
- **Setup screen** — enter the portal address once (`Test connection` pings `/api/health`);
  stored in SharedPreferences, pairing state lives in the WebView's localStorage/IndexedDB

**Get the APK without installing anything:** every push builds it in CI — GitHub → Actions →
latest run → artifact `screenpilot-player-apk`. Or build locally by opening `android-tv/` in
Android Studio (`./gradlew assembleDebug`).

**Install on a TV:** enable "install unknown apps", sideload `app-debug.apk` (USB drive or
`adb install`), open it, enter your server address, then pair from the portal with the
6-character code. Done — it survives reboots.

## Architecture notes

- **RBAC + group scoping** — every screen/schedule/report query is filtered by the user's allowed
  screen groups (SUPER_ADMIN and users with no explicit groups see everything). Role hierarchy:
  SUPER_ADMIN > ADMIN > CONTENT_MANAGER > VIEWER.
- **Timezone** — all scheduling logic is IST (`Asia/Kolkata`); the DB stores UTC. Both the server
  and the player evaluate windows in IST, so content switches on time even between pushes.
- **Real-time** — STOMP over WebSocket: `/topic/portal/screens` streams live status to the portal;
  `/topic/screen/{id}` carries schedule/playlist/layout updates and remote commands to each player.
  Heartbeats ride the same socket (HTTP POST fallback).
- **Offline player** — IndexedDB media cache via a download-manager queue (progress reported in
  heartbeats), config cached in localStorage for offline boots, proof-of-play queued in IndexedDB
  and batch-synced.
- **Storage** — local disk behind a `StorageService` interface (swap in S3 without touching
  callers). Media streaming supports HTTP Range so videos seek.
- **Integrations** — `ContentSourceProvider` interface with registered-but-disabled providers for
  Canva, Google Drive, OneDrive and Power BI (Settings shows the cards; each needs real API
  credentials — intentionally not faked).
- **Consistent errors** — global exception handler returns
  `{timestamp, status, error, message, path, fieldErrors?}` for every failure.

## Security

- **Signed media URLs** — media binaries (`/api/media/{id}/file|thumb`) and screenshots need no
  login (browser `<img>`/`<video>` tags can't send Authorization headers) but require an
  **HMAC-SHA256 signature with expiry** (`?exp=&sig=`) minted by the API. Leaked links die.
- **Hashed device tokens** — the DB stores only `SHA-256(token)`; devices send the plaintext,
  the server hashes and compares. A stolen database cannot impersonate screens.
- **Secrets via environment** — copy `.env.example` to `.env` and set `APP_JWT_SECRET`
  (≥32 chars) and `POSTGRES_PASSWORD`. Without it, Docker refuses to start the backend; bare
  local runs generate an ephemeral secret (sessions reset on restart).
- **Rate limiting** — in-memory per-IP sliding windows on `/api/auth/login`, `/api/auth/refresh`
  and the pairing endpoints answer HTTP 429 to brute-force attempts.

## API sketch

Auth `POST /api/auth/login|refresh` · Screens `GET|POST|PUT|DELETE /api/screens`,
`POST /api/screens/pair`, `POST /api/screens/{id}/commands`, `GET /api/screens/{id}/content|screenshot` ·
Media `POST/GET /api/media`, `GET /api/media/{id}/file|thumb`, `GET /api/media/{id}/usage` ·
Playlists `CRUD /api/playlists` + `PUT /api/playlists/{id}/items` · Layouts `CRUD /api/layouts` ·
Schedules `CRUD /api/schedules`, `POST /api/schedules/preview-conflicts` · Reports
`GET /api/reports/proof-of-play|uptime`, `GET /api/reports/export?report=…&format=xlsx|pdf` ·
Player (device-token auth) `GET /api/player/config`, `POST /api/player/heartbeat|logs|screenshot`,
`POST /api/player/pair/request`, `GET /api/player/pair/poll/{code}`.
