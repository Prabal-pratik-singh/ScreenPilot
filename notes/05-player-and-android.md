# Chapter 5 — The Player (offline-first) & the Android TV App

The player is where this project is most interesting: a web app engineered to behave like an
appliance. Everything lives in `frontend/src/player/`; the native shell in `android-tv/`.

---

## 5.1 The player as a state machine

`PlayerPage.jsx` is the orchestrator. At any moment the TV is in exactly one visible state:

```
                    ┌──────────────┐
       no device    │   PAIRING     │  shows the big 6-character code,
      ┌────────────▶│   screen      │  polls the server every 3s
      │             └──────┬───────┘
      │                    │ admin pairs it in the portal → device token saved
      │                    ▼
      │             ┌──────────────┐
      │   401 from  │   IDLE        │  branded screen + IST clock,
      │   server    │   screen      │  "Waiting for scheduled content…"
      │  (screen    └──────┬───────┘
      │   deleted)         │ a schedule matches "now"
      │                    ▼
      │             ┌──────────────┐
      │             │  PREPARING    │  needed media still downloading —
      │             │  screen       │  shows per-file progress (n/total, %)
      │             └──────┬───────┘
      │                    │ enough content is playable
      │                    ▼
      │             ┌───────────────────────────────┐
      └─────────────│  PLAYING                      │
                    │  PlaylistPlayer (single loop) │
                    │  or LayoutRenderer (zones)    │
                    └───────────────────────────────┘

  invisible background loops, always running once paired:
    · heartbeat every 30 s          · config re-fetch every 5 min + on WS push + on 'online'
    · schedule re-check every 5 s   · proof-of-play log flush every 30 s
```

Constants from the code: `HEARTBEAT_MS = 30000`, `CONFIG_POLL_MS = 5 min`,
`SCHEDULE_TICK_MS = 5000`, `APP_VERSION = '1.2.0'`.

---

## 5.2 Pairing — how a dumb TV gets an identity

**The problem:** a brand-new TV has no keyboard and no account. How does it securely become
"Screen: Ranchi Main Road — Entrance"?

**The solution — a claim code** (same pattern as pairing a streaming stick):

1. TV opens `/player` → `POST /api/player/pair/request` → receives a **6-character code**
   and shows it huge on screen. The code alphabet deliberately has **no 0/O, 1/I/L**
   (`ABCDEFGHJKMNPQRSTUVWXYZ23456789`) — someone reads this code out loud across a store;
   ambiguous characters would cause typos. Codes expire after **15 minutes**.
2. An **ADMIN** in the portal: Screens → Add screen → types the code + name/city/group →
   `POST /api/screens/pair`. The backend creates the Screen row and generates a **device
   token** (48 random bytes) — storing only its **SHA-256 hash** (chapter 7).
3. The TV polls `GET /api/player/pair/poll/{code}` every 3 s; on success it receives the
   plaintext token **once**, saves it in localStorage, and flips to the Idle screen. The
   plaintext copy on the server is wiped after pickup/expiry by a cleanup job.

From now on, every player request carries the header `X-Device-Token: <token>` — the TV's
password, presented on every call, verified by hash comparison.

---

## 5.3 Config — one endpoint tells the TV everything

`GET /api/player/config` returns the TV's whole world: its schedules (with time windows,
days, priorities), the playlists/layouts they reference, and a `requiredMedia` list where
each file has a **pre-signed download URL** (chapter 7 §signed URLs).

Two resilience behaviors around it:

- **Cached for offline boots:** every successful config is stored in localStorage
  (`screenpilot.player.config`). If the TV reboots while the server is down, it plays from
  the cached config instead of showing an error.
- **401 means "I've been deleted":** if the portal deletes the screen, the next config call
  or heartbeat returns 401 → the player **un-pairs itself** (clears identity + cache) and
  returns to the pairing screen. The fleet stays manageable entirely from the portal.

---

## 5.4 The download manager — media into IndexedDB

`downloadManager.js` reconciles "what the config requires" with "what's on disk":

```
 new config arrives: requiredMedia = [A, B, C, D]
   │
   ▼
 compare with IndexedDB:                 already have: A, B
   evict anything NOT required           (old file E deleted — space freed)
   queue the missing:                    [C, D]
   │
   ▼
 download ONE AT A TIME (sequential):    C: ██████████░░░░ 68%
   streaming fetch, progress % emitted   D: waiting…
   only when the integer % changes       (per-file status: pending → downloading
   │                                      → downloaded | failed)
   ▼
 store blob in IndexedDB → hand out URL.createObjectURL(blob) for playback
```

**Why sequential, not parallel?** A store's internet is often a thin mobile hotspot. Three
parallel 200 MB downloads would starve the heartbeat and any currently-streaming item.
One-at-a-time is slower but predictable.

**Why report status upward?** The per-file states are summarized into every heartbeat
(`mediaState: {cached[], downloading[{id, progress}], failed[]}`) — which is exactly what the
portal's Screen Detail page renders as per-creative download badges. When a screen shows
"Summer Promo — failed", you know *before* the customer calls.

A **generation counter** guards against config races: if a new config arrives mid-download,
the old download loop notices its generation is stale and exits — no zombie downloads.

---

## 5.5 The schedule engine — deciding what plays, locally, in IST

`scheduleEngine.js` answers one question every 5 seconds: *"given all my schedules, what
should be on screen right now?"*

```
 for each schedule:                                is it LIVE now?
   date range      (dateFrom ≤ today ≤ dateTo)          ─ no → skip
   day of week     (today ∈ {MON,TUE,...} or unset)     ─ no → skip
   time window     allDay → yes
                   else start ≤ now < end
                   overnight windows handled:  22:00–02:00 means
                   (now ≥ 22:00) OR (now < 02:00)        ← wraps midnight
 among LIVE schedules pick the winner:
   1. timed windows beat all-day loops     ← the layering rule
   2. then higher priority
   3. then most recently updated
```

**The timezone trick:** "now" comes from `Intl.DateTimeFormat` with
`timeZone: 'Asia/Kolkata'` — so a TV whose Android clock is set to UTC or Nepal time still
flips content at 18:00 **IST**. The backend's `TimeUtil.java` implements the *identical*
rules (including the overnight wrap), so server and player always agree.

**Why evaluate on the device at all?** So the 18:00 switch happens even if the server is
unreachable *at that moment*. The server ships rules; the player applies them. (Chapter 2
§offline-first.)

---

## 5.6 Double-buffered playback — why the loop never flashes black

`PlaylistPlayer.jsx` renders **two stacked layers** and alternates them:

```
   LAYER FRONT (opacity 1)  ← currently playing:  video "Summer Promo.mp4"
   LAYER BACK  (opacity 0)  ← already loaded:     image "Evening Offer.jpg"

   when the video ends:
     1. swap opacities (CSS transition, 400 ms crossfade)  ← viewer sees a clean fade
     2. after the fade +100ms, quietly load the NEXT item into the now-hidden layer
        (waiting until after the fade avoids competing for bandwidth/decode mid-fade)
```

This is **double buffering** — the same technique games use. A single `<video>` element
swapped in place would flash black/white between items; signage must look broadcast-smooth.

**Timing rules per item type:**
- **Videos** play full length — advance on the `ended` event (duration field is ignored) —
  with a **30-minute safety timer** in case `ended` never fires (codec stall on cheap boxes).
- **Images/PDF** show for their configured seconds (default 10).
- **External URLs/YouTube** get default 20 s; YouTube embeds mount **only when visible** so
  autoplay starts on cue; a generic URL that hasn't loaded in 12 s is skipped (a dead website
  cannot hold the screen hostage).

**Self-defense everywhere:** items whose media isn't downloaded yet are skipped; external
items are skipped while offline; a `<video>` error advances immediately; if *nothing* is
playable the component renders nothing and the Preparing/Idle screen shows. The loop is
built so that **no single bad item can freeze the TV**.

---

## 5.7 Multi-zone layouts on screen

`LayoutRenderer.jsx` turns a Layout (designed in the portal) into absolutely-positioned zones:

```
 ┌───────────────────────────────────────────────┐
 │                          │                    │   zone rects are stored as
 │   MEDIA zone             │   MEDIA zone       │   PERCENTAGES (x,y,w,h of the
 │   (own PlaylistPlayer,   │   (sidebar loop)   │   canvas), so the same layout
 │    own proof-of-play)    │                    │   fits 1080p, 4K, portrait…
 │                          ├────────────────────┤
 │                          │  WIDGET: clock IST │
 ├──────────────────────────┴────────────────────┤
 │  TICKER: "Diwali sale starts Friday · 50% …"  │  ← CSS animation, text duplicated
 └───────────────────────────────────────────────┘     twice for a seamless wrap
```

- Each **MEDIA** zone runs its **own independent `PlaylistPlayer`** (own loop, own
  proof-of-play logs tagged with that zone's playlist).
- **TICKER** renders its message twice and animates `translateX(-50%)` — when the first copy
  scrolls out, the second is exactly in place → an infinite belt with pure CSS.
- **WIDGET** offers clock/date (IST); the weather widget is an honest placeholder (hardcoded
  value, labeled as such — wiring a real weather API is future work).
- **LOGO** zones resolve their image **through the download manager** like any media (cached,
  offline-safe); **WEB** zones show a sandboxed iframe with a failure placeholder.

The designer (`LayoutDesignerPage.jsx`) and the renderer share the same coordinate model
(percentages + z-order), which is what makes the designer preview **WYSIWYG** — what you drag
is literally what the TV computes.

---

## 5.8 Proof-of-play — evidence that survives anything

Every time an item finishes playing, the player records a log entry:

```json
{ "itemTitle": "Summer Promo", "itemType": "MEDIA", "mediaId": "...", "screenId": "...",
  "playlistId": "...", "scheduleId": "...", "startedAt": "...", "endedAt": "..." }
```

The delivery pipeline is built for unreliable networks (**at-least-once delivery**):

```
 item finishes → append log to INDEXEDDB queue          (survives reboot & offline)
                        │
        every 30 s / on reconnect:
                        ▼
        take up to 200 oldest → POST /api/player/logs
                        │
              ┌─────────┴──────────┐
        server says OK        request failed / offline
              │                     │
   delete EXACTLY those      keep them queued —
   rows from the queue       they'll retry next flush
```

The delete-only-after-acknowledgment rule means a lost response re-sends the batch rather
than losing it — billing evidence is never dropped. (Duplicates on a re-send are the accepted
trade-off of at-least-once delivery — far better than silently missing plays.)

---

## 5.9 Heartbeats, live status, and remote commands

Every 30 s the player reports: playing/idle, current item, app version, storage used/total
(`navigator.storage.estimate()`), and the media download states. Transport: **prefer the
WebSocket** (already open, nearly free), **fall back to HTTP POST** if the socket is down.
The server flips a screen OFFLINE after **90 s of silence** (3 missed beats — one lost packet
doesn't cause a false alarm; a real outage is noticed within ~1½ minutes).

**Remote commands** arrive over the per-screen topic:
- `RELOAD` — acknowledge first, then `window.location.reload()` 300 ms later (the ack must
  escape before the page dies).
- `CLEAR_CACHE` — wipe IndexedDB media + re-download everything (the fix for a corrupted file).
- `SCREENSHOT` — draw the current `<video>`/`<img>` frame onto a canvas → JPEG → upload.
  Honest limitation, documented in the code: CSS-composed content (tickers, widgets) can't be
  captured without heavyweight libraries, so those render a placeholder card instead.
  Screenshots are best-effort by design.

---

## 5.10 The Android TV app — a kiosk shell around the web player

`android-tv/` is a **Kotlin** app whose `dependencies { }` block is **literally empty** —
zero external libraries, ~1 MB APK.

**The central design decision — wrap, don't rewrite:** all the hard logic above (downloads,
schedules, double buffering, logs) already exists in the web player and is updated **by
redeploying the server** — no APK rollout to 200 physical TVs. The native app adds only what
a plain browser can't do on a TV:

| Capability | Implementation | Why |
|---|---|---|
| **Start on power-on** | `BootReceiver` listens for `BOOT_COMPLETED` (+ the `QUICKBOOT_POWERON` variant some cheap boxes send instead); skips if no server configured yet | Plug the box in → signage plays. Nobody presses anything. |
| **Never sleep, truly fullscreen** | `FLAG_KEEP_SCREEN_ON` + immersive-sticky fullscreen theme | It's an appliance, not a tablet |
| **Autoplay without a remote press** | `mediaPlaybackRequiresUserGesture = false` on the WebView | Browsers block autoplay until a user gesture — a TV has no user |
| **Survive renderer crashes** | `onRenderProcessGone` → destroy and **rebuild the WebView** after 1 s (it's created in code, not XML, precisely so it can be rebuilt) | Low-end boxes OOM-kill WebView renderers; the show must go on |
| **Survive network/server outages** | Error screen + retry with **exponential backoff** 5s → 10s → 20s → 40s → capped 60s; a `ConnectivityManager` callback reloads **immediately** when the network returns | Waiting out a 60 s backoff after Wi-Fi returns would be silly |
| **Kiosk BACK handling** | BACK is swallowed; pressing **BACK 5× within 3 s** opens the hidden setup screen | Accidental remote presses must not exit signage; technicians still get a service door |
| **One-field setup** | Enter the portal URL once; "Test connection" pings `GET /api/health`; saved in SharedPreferences | `/api/health` does a real DB round-trip, so "reachable" means the whole stack works |
| **Plain HTTP allowed** | `network_security_config.xml` permits cleartext | Real deployments start as `http://<LAN-IP>` before TLS exists; Android blocks cleartext by default since API 28 |

**Why zero dependencies?** The build file's own comment: plain Activity + WebView keeps the
APK ~1 MB and compatible with old boxes (**minSdk 21**, Android 5.0). Consequences visible in
code: framework `Activity` instead of AppCompat, `HttpURLConnection` + a thread instead of
Retrofit, deprecated-but-universal fullscreen APIs instead of the modern insets controller.
Every choice trades "modern" for "runs on the ₹2,500 TV stick in the field".

**Where state lives:** the native app remembers **only the server URL**. Pairing identity and
media cache live *inside the WebView's* localStorage/IndexedDB — the same storage the web
player always uses. The shell is stateless; the web app is the brain.

**Distribution:** every git push builds the APK in CI (GitHub Actions artifact
`screenpilot-player-apk`) — installers sideload it, enter the URL, pair with the 6-character
code, done. Survives reboots from then on.

---

**Next:** [Chapter 6 — Docker, nginx, CI & testing →](06-infrastructure-ci-testing.md)
