# Chapter 8 — The Database Design

Schema lives in `src/main/resources/db/migration/` (Flyway, chapter 3) and maps to the
entities in `domain/`. PostgreSQL 16 holds everything except the media files themselves
(those are on disk; the DB stores their *paths*).

---

## 8.1 The ER diagram (entity–relationship)

```
                            ┌──────────────┐
                            │    users      │  role: SUPER_ADMIN/ADMIN/
                            │──────────────│        CONTENT_MANAGER/VIEWER
                            │ id (UUID)     │  password_hash (BCrypt)
                            └──────┬───────┘  active flag
                                   │ many-to-many  ("which groups may this user see?"
                                   ▼               empty = sees everything)
                       ┌───────────────────────┐
                       │  user_group_access     │  join table
                       └──────────┬────────────┘
                                  ▼
   ┌──────────────┐ 1      * ┌──────────────┐
   │ screen_groups │◀────────│   screens     │  status ONLINE/OFFLINE · paired flag
   │ (Ranchi/…)    │         │──────────────│  device_token (SHA-256 HASH, unique)
   └──────────────┘         │ id (UUID)     │  live telemetry: last_heartbeat_at,
                            └──┬────────▲──┘  current_item_name, storage_used_mb,
                               │        │      media_state (JSON text)
              targeted by      │        │ pairing handshake
                               │        │
                        ┌──────▼─────┐ ┌┴──────────────┐
                        │ schedule_  │ │ pairing_codes  │  code (6 chars) · status
                        │ targets    │ │                │  PENDING/PAIRED/EXPIRED
                        └──────▲─────┘ │                │  device_token_plain
                               │       └────────────────┘  (held ONLY until pickup)
   ┌──────────────┐ *       1 │
   │  schedules    │───────────┘         content_type = PLAYLIST or LAYOUT →
   │──────────────│                      exactly ONE of these is set:
   │ all_day       │ *                1 ┌──────────────┐
   │ start/end TIME│───────────────────▶│  playlists    │
   │ days_of_week  │  or                └──────┬───────┘
   │ date range    │ *                1        │ 1
   │ priority      │──────────┐               ▼ *   (ordered by position)
   │ active        │          │        ┌──────────────┐ *       1 ┌──────────────┐
   └──────────────┘          │        │playlist_items │──────────▶│ media_assets  │
                              ▼        │──────────────│           │──────────────│
                       ┌──────────────┐│ item_type:    │           │ type VIDEO/   │
                       │   layouts     ││ MEDIA/URL/    │           │ IMAGE/PDF     │
                       │──────────────││ YOUTUBE       │           │ storage_path  │
                       │ orientation   ││ duration_secs │           │ thumb_path    │
                       └──────┬───────┘└──────────────┘           │ width/height/ │
                              │ 1                                  │ duration      │
                              ▼ *                                  │ deleted flag  │
                       ┌──────────────┐          may reference     │ (SOFT delete) │
                       │ layout_zones  │─────────────────────────▶└──────────────┘
                       │──────────────│  (MEDIA zones point at a playlist;
                       │ type: MEDIA/  │   LOGO zones reference a media id
                       │ TICKER/WIDGET/│   inside their JSON config)
                       │ LOGO/WEB      │
                       │ x,y,w,h (%)   │  ← percentages of the canvas!
                       │ z, config JSON│
                       └──────────────┘

   APPEND-ONLY "FACT" TABLES (reference screens by plain UUID — no JPA relations):
   ┌────────────────────┐  ┌──────────────────────┐  ┌───────────────────┐
   │ playback_logs       │  │ screen_status_events  │  │ screen_commands    │
   │ (proof-of-play)     │  │ ONLINE/OFFLINE        │  │ RELOAD/CLEAR_CACHE │
   │ BIGSERIAL id        │  │ transitions only      │  │ /SCREENSHOT        │
   │ item title COPIED   │  │ → uptime reports      │  │ SENT→ACKED→        │
   │ started/ended, secs │  │                       │  │ COMPLETED          │
   └────────────────────┘  └──────────────────────┘  └───────────────────┘
```

---

## 8.2 Each table in one breath

| Table | What one row means |
|---|---|
| `users` | A portal login: email, BCrypt password hash, role, active flag |
| `screen_groups` | A named bundle of screens (e.g. "Ranchi") used for both organization and permissions |
| `user_group_access` | "User X is allowed to see group Y" (no rows = user sees everything) |
| `screens` | One physical TV: identity, location (state/city/coords for the map), pairing state, the device-token **hash**, and live telemetry updated by heartbeats |
| `pairing_codes` | One pairing attempt: the 6-char code, its status/expiry, and — only briefly — the plaintext token awaiting pickup |
| `media_assets` | One uploaded file: type, storage path, probed width/height/duration, folder/tags, soft-delete flag |
| `playlists` / `playlist_items` | A loop and its ordered items; items are MEDIA (points at an asset), URL, or YOUTUBE, each with display seconds (null for videos = play full length) |
| `layouts` / `layout_zones` | A multi-zone screen design; zones store type, percentage rect, z-order, and a per-type JSON `config` blob |
| `schedules` / `schedule_targets` | "Play THIS (playlist or layout) on THESE screens WHEN" — all-day or a time window, optional days-of-week and date range, priority, active flag |
| `playback_logs` | Proof-of-play: one completed play of one item on one screen |
| `screen_status_events` | One ONLINE↔OFFLINE transition — the raw material of uptime reports |
| `screen_commands` | One remote command and its lifecycle (SENT → ACKED → COMPLETED, screenshots store a result path) |

---

## 8.3 The design decisions (each with its why)

**1. UUID primary keys, assigned in Java** (`this.id = UUID.randomUUID()`).
Why: the object has an identity *before* hitting the DB (simplifies code and WebSocket
payloads), ids are unguessable (no `/api/screens/1,2,3` crawling), and they never collide
across environments. **Exception:** `playback_logs` and `screen_status_events` use
auto-incrementing `BIGSERIAL` — they're high-volume append-only rows where a compact,
DB-generated integer is cheaper and nothing references them.

**2. Append-only fact tables with no JPA relationships.**
The three history tables reference screens by plain UUID, not `@ManyToOne`. Why: they are
*history*, not object graphs — written once, then only range-scanned by time. Skipping ORM
relations avoids accidental lazy-loads of thousands of rows, and history survives its
subject's deletion (a deleted screen's plays still show in old reports as
"(removed screen)").

**3. Status *events*, not status *samples*.** The uptime table records only **transitions**
(went online / went offline), not "still online" every minute. A screen with a stable day
produces 2 rows, not 1,440. Uptime % is reconstructed by replaying the events over the day —
tiny table, exact math.

**4. Denormalized proof-of-play.** Each `playback_logs` row **copies** the item's title/type
at play time. Normalization purists would join to `media_assets` instead — but then renaming
or deleting media would silently rewrite/destroy last month's billing report. Reports must be
immutable history; copying at write time guarantees it.

**5. Soft delete for media** (`deleted` flag, file kept). Why: playlists may still reference
the asset; players may still have it cached; old reports still mention it. The portal's
delete flow even calls `GET /api/media/{id}/usage` first to warn "used in these playlists."

**6. The partial unique index** (chapter 3 §Postgres):
`UNIQUE ... WHERE status='PENDING'` on pairing codes — uniqueness only *while claimable*.
Expired codes keep their value for history without blocking reuse of the code space.

**7. Indexes follow the queries.** `(screen_id, started_at)` on playback logs (per-screen
report ranges), `(started_at)` (whole-fleet ranges), `(state, city)` on screens (the
dashboard's location tree), `(screen_id, created_at DESC)` on commands (recent-first panel).
An index is a sorted lookup structure that turns "scan everything" into "jump straight
there" — each one here matches a real query in the code.

---

## 8.4 The timezone strategy (worth a section of its own)

Signage scheduling is *wall-clock* business ("6 PM in the store"), but servers and databases
live in UTC. The project splits time into two kinds and never mixes them:

```
 MOMENTS (something happened)             WALL-CLOCK RULES (when things should happen)
 ────────────────────────────             ─────────────────────────────────────────────
 stored as UTC instants                   stored as plain TIME / DATE columns
 e.g. last_heartbeat_at,                  e.g. schedules.start_time = 18:00
      playback started_at                      (meaning 18:00 IST, by contract)
 → convert to IST only for display        → evaluated against the IST clock
                                            (TimeUtil.java on the server,
                                             scheduleEngine.js on the player —
                                             identical rules, including overnight
                                             windows like 22:00–02:00)
```

Why not store schedule times as UTC too? Because "18:00 IST" as UTC would be "12:30", and
any future daylight-savings-style change or server-timezone difference would silently shift
what plays. Keeping rules as wall-clock text + pinning the interpretation zone (`Asia/Kolkata`)
makes the behavior permanent and identical on server and TV — regardless of what timezone
the server box or the TV's Android settings are in.

---

## 8.5 The migrations as a project timeline

The seven Flyway files double as the project's history — each phase shipped one vertical
slice of the product:

| Migration | Phase it shipped |
|---|---|
| V1 | Identity & fleet: users, groups, screens, pairing |
| V2 | Content: media library, playlists |
| V3 | The core product: scheduling + proof-of-play logging |
| V4 | Multi-zone layouts |
| V5 | Operations: remote commands + uptime history |
| V6 | The rebrand (data-only; written to no-op on fresh databases) |
| V7 | Security hardening: device-token hashing, zero-downtime |

---

**Next:** [Chapter 9 — End-to-end flows as sequence diagrams →](09-flows.md)
