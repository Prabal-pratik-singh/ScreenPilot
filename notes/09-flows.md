# Chapter 9 — End-to-End Flows (sequence diagrams)

Each diagram below shows every actor a feature touches, in time order (top to bottom).
Reading these after chapters 1–8 ties the whole system together.

---

## 9.1 Logging in

```
 YOU               PORTAL (React)            BACKEND                       DATABASE
  │  type email +      │                        │                             │
  │  password          │                        │                             │
  │───────────────────▶│ POST /api/auth/login   │                             │
  │                    │───────────────────────▶│ find user by email          │
  │                    │                        │────────────────────────────▶│
  │                    │                        │  BCrypt-compare password    │
  │                    │                        │  (rate limit: 10/min/IP)    │
  │                    │   200 { accessToken,   │                             │
  │                    │◀───────  refreshToken, │                             │
  │                    │          user }        │                             │
  │                    │ save tokens in localStorage ('screenpilot.tokens')   │
  │                    │ every later request: Authorization: Bearer <access>  │
  │◀── dashboard ──────│                        │                             │
```

After 30 minutes the access token expires → any call returns 401 → the axios interceptor
silently refreshes (one shared refresh even for parallel failures — chapter 4) and replays.

---

## 9.2 Pairing a new TV

```
 TV (player app)                 BACKEND                        ADMIN (portal)
  │ POST /api/player/pair/request  │                                │
  │───────────────────────────────▶│ create pairing_codes row       │
  │      { code: "K7M2XQ",         │ (PENDING, expires in 15 min)   │
  │◀─────  pollIntervalMs: 3000 }  │                                │
  │                                │                                │
  │ shows K7M2XQ in huge digits    │          Screens → Add screen  │
  │                                │          types K7M2XQ + name,  │
  │ GET /pair/poll/K7M2XQ  (3s)    │          city, group, coords   │
  │───────────────────────────────▶│◀───────────────────────────────│
  │◀── { status: PENDING } ────────│  POST /api/screens/pair        │
  │ GET /pair/poll/K7M2XQ          │  · create screens row          │
  │───────────────────────────────▶│  · generate 48-byte token      │
  │                                │  · store SHA-256(token) ONLY   │
  │   { status: PAIRED,            │  · park plaintext on the       │
  │◀────  deviceToken: "kX92…" }   │    pairing row for pickup      │
  │ save token in localStorage     │  (plaintext wiped after        │
  │ → idle screen, heartbeats begin│   pickup/expiry by a sweeper)  │
```

---

## 9.3 Uploading media

```
 CONTENT MANAGER          BACKEND                                  DISK
  │ drag Summer.mp4          │                                       │
  │ POST /api/media          │ checks: ≤500 MB, extension in         │
  │  (multipart, progress    │ allow-list → type = VIDEO             │
  │   bar via axios          │                                       │
  │   onUploadProgress)      │ store as uploads/media/<uuid>.mp4 ───▶│
  │─────────────────────────▶│ ffprobe: duration 30s, 1920×1080      │
  │                          │ ffmpeg: frame @0.5s → 480px thumb ───▶│
  │                          │ (ffmpeg missing? → branded            │
  │                          │  placeholder; upload still succeeds)  │
  │   200 { …, fileUrl:      │ save media_assets row                 │
  │◀── "/api/media/<id>/file │                                       │
  │     ?exp=…&sig=…" }      │  ← the URL arrives pre-signed         │
```

---

## 9.4 Publishing a schedule (the flagship flow)

```
 CONTENT MANAGER        BACKEND                                PLAYER (each targeted TV)
  │ wizard: playlist →     │                                       │
  │ tick screens →         │                                       │
  │ 18:00–22:00 weekdays   │                                       │
  │                        │                                       │
  │ POST /preview-conflicts│  compare against active schedules     │
  │───────────────────────▶│  on the same screens:                 │
  │                        │  · timed vs timed overlap → CONFLICT  │
  │◀── conflicts[] ────────│  · timed over all-day → NOT a         │
  │  (modal: "Override &   │    conflict (intentional layering —   │
  │   publish" deactivates │    timed wins during its hours)       │
  │   the listed ones)     │                                       │
  │ POST /api/schedules    │  save (one transaction)               │
  │───────────────────────▶│  AFTER COMMIT:                        │
  │                        │  /topic/screen/{id} ─────────────────▶│ {type: SCHEDULES_UPDATED}
  │                        │                                       │ GET /api/player/config
  │                        │◀──────────────────────────────────────│
  │                        │  config incl. requiredMedia ─────────▶│ download new media
  │                        │                                       │ → IndexedDB (progress
  │                        │   (portal watches download badges     │   visible in portal)
  │                        │    arrive via heartbeats)             │ at 18:00 IST the local
  │                        │                                       │ engine switches content
  │                        │                                       │ — even if offline then
```

Editing a playlist while it plays follows the same tail: save → AFTER-COMMIT push
(`PLAYLIST_UPDATED`) → config refetch → the loop hot-swaps without going black (the
double-buffer just rebuilds its hidden layer).

---

## 9.5 Heartbeats & offline detection

```
 PLAYER                        BACKEND                          PORTAL (all open tabs)
  │ every 30s:                    │                                 │
  │ heartbeat {status, current    │ update screens row (telemetry)  │
  │  item, storage, mediaState}   │ was it OFFLINE? → also write    │
  │──────────────────────────────▶│ screen_status_events(ONLINE)    │
  │  (WebSocket preferred,        │ push SCREEN_UPDATED ───────────▶│ map dot stays green,
  │   HTTP POST fallback)         │                                 │ "now playing" updates
  │                               │                                 │
  ✕ power cut in the store        │ sweep job (every 15s):          │
                                  │ last heartbeat > 90s ago?       │
                                  │ → status = OFFLINE              │
                                  │ → screen_status_events(OFFLINE) │
                                  │ → push SCREEN_UPDATED ─────────▶│ dot flips RED, live;
                                  │                                 │ "Offline for 2m" starts
                                  │                                 │ counting
```

Those status events later power the uptime report: per-day online %, worst-performers
red-flag list, exportable to Excel/PDF.

---

## 9.6 Proof-of-play, TV → report

```
 PLAYER                                BACKEND                     REPORTS PAGE
  │ "Summer Promo" finishes playing      │                            │
  │ → append log row to IndexedDB        │                            │
  │   (survives offline & reboots)       │                            │
  │ every 30s / on reconnect:            │                            │
  │ POST /api/player/logs {logs:[…200]}  │ insert playback_logs rows  │
  │─────────────────────────────────────▶│ (titles COPIED so reports  │
  │◀── 200 OK ───────────────────────────│  survive later deletions)  │
  │ delete exactly those rows from queue │                            │
  │ (failure? keep them — retry later:   │      GET /api/reports/     │
  │  at-least-once delivery)             │◀─────  proof-of-play       │
                                         │  group by creative×screen: │
                                         │  plays, seconds, first/last│
                                         │────────────────────────── ▶│ table + plays/day chart
                                         │  GET /api/reports/export?format=xlsx|pdf
                                         │  → Apache POI / openhtmltopdf, branded
```

---

## 9.7 Remote command: screenshot

```
 ADMIN (screen detail page)     BACKEND                         PLAYER
  │ click "Request screenshot"     │                               │
  │ POST /screens/{id}/commands    │ create screen_commands row    │
  │───────────────────────────────▶│ (SENT)                        │
  │                                │ /topic/screen/{id} ──────────▶│ {type: COMMAND,
  │                                │                               │  command: SCREENSHOT}
  │                                │◀── POST /commands/{id}/ack ───│ (row → ACKED)
  │                                │                               │ draw current frame on a
  │                                │                               │ canvas → JPEG (70%)
  │                                │◀── POST /player/screenshot ───│
  │  (page re-checks after 5s)     │ store file, row → COMPLETED   │
  │ GET /screens/{id}/screenshot   │                               │
  │  ?exp=…&sig=…  (15-min link)   │                               │
  │◀── the image ──────────────────│                               │
```

`RELOAD` and `CLEAR_CACHE` ride the same rails, minus the upload step.

---

## 9.8 What happens when the internet dies (the resilience story in one flow)

```
  09:00  store Wi-Fi dies
  09:00+ player: WebSocket drops; heartbeats fail; nothing visible changes on the TV —
         the loop keeps playing from IndexedDB; proof-of-play keeps queueing locally
  09:01½ portal: screen flips OFFLINE (90s rule); "Offline for …" counter runs
  12:00  someone reboots the TV box — still offline:
         Android app auto-starts (BOOT_COMPLETED) → WebView loads → SPA boots →
         config loads from localStorage cache → media plays from IndexedDB. Still fine.
  18:00  schedule engine (local, IST) switches to Evening Offers — offline. On time.
  19:30  Wi-Fi returns:
         Android's network callback reloads instantly (skips the backoff wait) →
         WebSocket reconnects → heartbeat → portal flips ONLINE →
         queued logs flush in batches → config refetched → any missed changes applied.
  Result: zero black screens, zero lost proof-of-play, zero manual intervention.
```

---

**Next:** [Chapter 10 — Glossary & the decision cheat-sheet →](10-glossary.md)
