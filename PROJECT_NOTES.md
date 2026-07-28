# ScreenPilot — The Complete Project Guide (Start Here)

> **What is this?** A book-style, plain-language guide to the whole ScreenPilot codebase.
> Every technology is **defined from zero** (what is an API? what is JWT? what is Docker?),
> then explained with **why this project uses it** and a **real example from the code**,
> plus diagrams for the architecture and every important flow.
>
> Read it top-to-bottom for the full story, or jump straight to a chapter.

---

## The chapters

| # | File | What you'll learn |
|---|---|---|
| 1 | [notes/01-foundations.md](notes/01-foundations.md) | The absolute basics: frontend vs backend vs database, **what an API is**, **REST**, **JSON**, **HTTP**, what a **SPA** is, **monolith vs microservices** — each defined simply with examples from this project |
| 2 | [notes/02-architecture.md](notes/02-architecture.md) | The full system design with diagrams: how the portal, player, backend, database and Android app connect; the journey of one request; the backend's **layered architecture** |
| 3 | [notes/03-backend-tech.md](notes/03-backend-tech.md) | Every backend technology defined + why + example: Java, **Spring Boot**, **JPA/Hibernate (ORM)**, **PostgreSQL**, **Flyway**, **JWT**, **WebSocket/STOMP**, **ffmpeg**, Apache POI, openhtmltopdf, **Maven** |
| 4 | [notes/04-frontend-tech.md](notes/04-frontend-tech.md) | Every frontend technology defined + why + example: **React**, **Vite**, **React Router**, **TanStack Query**, **axios**, **Tailwind CSS**, **@dnd-kit**, **Leaflet**, **Recharts**, browser storage (**localStorage vs IndexedDB**) |
| 5 | [notes/05-player-and-android.md](notes/05-player-and-android.md) | The offline-first player (state machine, download manager, double-buffered playback, IST schedule engine, proof-of-play queue) and the Android TV kiosk app |
| 6 | [notes/06-infrastructure-ci-testing.md](notes/06-infrastructure-ci-testing.md) | **Docker** (containers, images, volumes), docker-compose services, **nginx reverse proxy**, backups, **CI/CD with GitHub Actions**, and the testing strategy (**Testcontainers**) |
| 7 | [notes/07-security.md](notes/07-security.md) | Security from first principles: **hashing vs encryption**, **BCrypt**, **JWT** deep-dive, **HMAC-signed URLs**, hashed device tokens, **rate limiting**, **CORS**, **RBAC** |
| 8 | [notes/08-database.md](notes/08-database.md) | The database design: every table and why it exists, the **ER diagram**, migrations V1–V7, indexes, soft delete, the UTC-vs-IST timezone strategy |
| 9 | [notes/09-flows.md](notes/09-flows.md) | End-to-end **sequence diagrams**: login, pairing a TV, uploading media, publishing a schedule, heartbeats & offline detection, proof-of-play, remote commands |
| 10 | [notes/10-glossary.md](notes/10-glossary.md) | A–Z glossary of every technical term, one simple definition each + the decision cheat-sheet table |

---

## What is ScreenPilot? (2 minutes)

ScreenPilot is a **digital signage platform**: software that controls the TVs/screens hanging
in stores and offices, and decides **what plays on them and when**.

**A concrete scenario.** Imagine you run 12 grocery stores across Ranchi, Patna and Kolkata.
Every store has a TV near the entrance, and you want to:

1. play a promo-video loop **all day** on every TV,
2. show a special "Evening Offers" banner **only from 6 PM to 10 PM**,
3. **prove** to the brands paying for ads that their ad really played 1,240 times last week
   (this industry term is **proof-of-play**),
4. get alerted when a store's TV **goes offline** (power cut, unplugged cable),
5. let a local manager in Ranchi manage **only Ranchi's screens** — nothing else.

Doing this by walking into stores with a USB stick doesn't scale. ScreenPilot does all five
from one dashboard. It is **one repository containing three applications**:

| Application | Where it runs | What it does |
|---|---|---|
| **Portal (CMS)** — a React web dashboard | Your laptop's browser | Manage screens, upload media, build playlists, design multi-zone layouts, publish schedules, manage users, view reports |
| **Player** — a fullscreen React web app at `/player` | On the TV (in a browser or WebView) | Shows a pairing code, downloads media for offline use, plays the right content at the right time, reports back constantly |
| **Backend** — a Spring Boot (Java) server | A server / Docker | The brain: one **REST API** + **WebSocket** hub + business rules + the only thing allowed to touch the database |

Plus a tiny native **Android TV app** (`android-tv/`, Kotlin, ~1 MB) that wraps the web player
in a kiosk shell so real TV boxes start it automatically on boot and never sleep.

---

## The master picture

Keep this one diagram in your head; every chapter zooms into a part of it.

```
                        YOU (admin / content manager / viewer)
                                        │
                                 browser, laptop
                                        │
        ┌───────────────────────────────▼───────────────────────────────┐
        │                    REACT PORTAL  (the dashboard)              │
        │   dashboards · screens · media · playlists · layouts ·        │
        │   schedules · reports · users                                 │
        └───────────────┬───────────────────────────▲──────────────────┘
                        │ ①  REST API calls          │ ②  live updates pushed
                        │    (HTTPS + JSON)          │     over WebSocket
                        ▼                            │
   ┌─────────────────────────────────────────────────────────────────────┐
   │                        NGINX  (traffic doorman)                     │
   │   serves the built React files, forwards /api → backend,           │
   │   forwards /ws → backend (WebSocket)                               │
   └───────────────────────────────┬─────────────────────────────────────┘
                                   ▼
   ┌─────────────────────────────────────────────────────────────────────┐
   │                 SPRING BOOT BACKEND  (Java 17) — the brain          │
   │                                                                     │
   │   REST controllers → services (business rules) → repositories       │
   │   + Security (JWT users, device tokens, signed URLs, rate limits)  │
   │   + WebSocket broker (STOMP topics)                                │
   │   + background jobs (offline sweeper, cleanup)                     │
   │   + media pipeline (ffmpeg thumbnails)  + report exports (xlsx/pdf)│
   └──────────┬───────────────────────────────┬──────────────────────────┘
              │ SQL                           │ files
              ▼                               ▼
   ┌─────────────────────┐          ┌─────────────────────┐
   │  POSTGRESQL 16       │          │  ./uploads folder   │
   │  all structured data │          │  media + thumbnails │
   └──────────▲──────────┘          └─────────────────────┘
              │ nightly pg_dump (gzip, keep 14 days)
   ┌──────────┴──────────┐
   │  BACKUP sidecar      │
   └─────────────────────┘

              ▲                            ▲
              │ ③ REST with device token   │ ④ WebSocket per-screen topic
              │    (config, heartbeat,     │    (schedule updates,
              │     logs, pairing)         │     remote commands)
        ┌─────┴────────────────────────────┴─────┐
        │        PLAYER on each TV               │
        │  React app inside a browser/WebView    │
        │  · caches media in IndexedDB           │
        │  · evaluates IST schedules locally     │
        │  · keeps playing when internet dies    │
        └────────────────────────────────────────┘
                          ▲
                          │ wrapped by (on real TVs)
        ┌─────────────────┴──────────────────────┐
        │  ANDROID TV APP (Kotlin kiosk WebView) │
        │  auto-start on boot · never sleeps ·   │
        │  self-healing retry · setup screen     │
        └────────────────────────────────────────┘
```

The four numbered arrows are the only kinds of communication in the whole system:

1. **Portal → Backend (REST):** "give me all screens", "create this schedule" — request/response.
2. **Backend → Portal (WebSocket):** "screen 7 just went offline" — server pushes without being asked.
3. **Player → Backend (REST):** "here's my heartbeat", "give me my config" — authenticated by a **device token**, not a user login.
4. **Backend → Player (WebSocket):** "your schedule changed, re-fetch", "take a screenshot now".

---

## The tech stack at a glance

| Layer | Technology | One-line reason (full reasons in the chapters) |
|---|---|---|
| Backend language | **Java 17** | Stable LTS; records for clean DTOs |
| Backend framework | **Spring Boot 3.3.4** | Web server + security + DB + WebSocket in one battle-tested framework |
| Database | **PostgreSQL 16** | Reliable relational DB; the data is naturally relational |
| DB migrations | **Flyway** | Schema changes as versioned, repeatable SQL files |
| Auth | **JWT (jjwt)** + BCrypt + HMAC signatures | Stateless login; safe media links; hashed device credentials |
| Real-time | **WebSocket + STOMP** | Push changes to screens instantly over named topics |
| Media tooling | **ffmpeg/ffprobe**, Thumbnailator, PDFBox | Video probing + thumbnails for every file type |
| Report exports | **Apache POI** (Excel), **openhtmltopdf** (PDF) | Branded downloadable reports |
| Frontend | **React 18 + Vite** | Interactive dashboard + long-running player app |
| Server state | **TanStack Query** | Caching + auto-refresh of API data across pages |
| Styling | **Tailwind CSS** | Utility classes + one brand palette everywhere |
| Maps / charts / DnD | **Leaflet / Recharts / @dnd-kit** | Live fleet map, report charts, drag-and-drop builders |
| Offline storage | **IndexedDB + localStorage** | Media cache + config cache = playback without internet |
| TV app | **Kotlin WebView kiosk, zero dependencies** | ~1 MB APK that survives reboots and crashes |
| Packaging | **Docker + docker-compose + nginx** | One-command startup; same-origin proxy |
| CI | **GitHub Actions** | Tests + builds + downloadable APK on every push |
| Tests | **Testcontainers** | Integration tests against a real throwaway Postgres |

---

## How to run it (quick reference)

```bash
# everything in Docker (needs .env with APP_JWT_SECRET — see .env.example)
docker compose up -d --build          # → http://localhost:8090  (player at /player)

# OR local development with hot reload:
docker compose up -d postgres         # database only (localhost:5433)
./mvnw spring-boot:run                # backend  → http://localhost:8081
cd frontend && npm install && npm run dev   # portal → http://localhost:5174
```

Demo logins (seeded on first start): `admin@screenpilot.in / ScreenPilot@123` (SUPER_ADMIN),
`content.ranchi@screenpilot.in / Content@123` (CONTENT_MANAGER, Ranchi only),
`viewer@screenpilot.in / Viewer@123` (read-only).

→ Now start with **[notes/01-foundations.md](notes/01-foundations.md)**.
