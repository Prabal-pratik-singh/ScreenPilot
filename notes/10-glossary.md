# Chapter 10 — Glossary (A–Z) & the Decision Cheat-Sheet

One-line, plain-language definitions of every technical term in this guide — then the
"why did we choose X?" table for rapid revision.

---

## Glossary

| Term | Plain-language meaning |
|---|---|
| **API** | The menu of requests one program allows another to make, and what it promises to answer |
| **At-least-once delivery** | A sending strategy where messages are re-sent until acknowledged — duplicates possible, losses not (used for proof-of-play) |
| **Axios** | A JavaScript HTTP client; its interceptors add the auth header and auto-refresh expired tokens |
| **BCrypt** | A deliberately-slow, salted password hasher — makes stolen hashes near-useless for guessing |
| **Bean** | An object Spring creates and wires for you (see Dependency Injection) |
| **Bind-mount** | A folder shared between your computer and a Docker container (`./uploads`) |
| **CI (Continuous Integration)** | Automation that builds + tests every push (GitHub Actions here) |
| **CORS** | Browser rule controlling which *other* websites' JavaScript may call your API |
| **CRUD** | Create, Read, Update, Delete — the four basic data operations |
| **CSRF** | An attack that abuses auto-attached cookies; irrelevant here because auth uses headers, not cookies |
| **Dependency Injection (DI)** | The framework constructs objects and hands them their dependencies — no `new` scattered everywhere |
| **DTO (Data Transfer Object)** | A class defining exactly what the API's JSON looks like — never the raw DB entity |
| **Double buffering** | Preparing the next frame/item on a hidden layer, then swapping — eliminates visible loading (the playlist crossfade) |
| **Entity** | A Java class mapped 1:1 to a database table (`Screen` ↔ `screens`) |
| **ER diagram** | A picture of tables and their relationships |
| **Eventual consistency** | A system that may briefly show stale data but is guaranteed to converge to correct (the push+pull config model) |
| **ffmpeg / ffprobe** | The industry-standard command-line tools for video processing / video metadata |
| **Flyway** | Runs versioned SQL migration files in order, exactly once each — the schema as code |
| **Foreign key** | A DB rule that a reference must point at a real row (no orphans) |
| **Framework** | Code that runs the show and calls *your* code in defined slots (Spring Boot, React) |
| **Hashing** | One-way scrambling; you can compare but never reverse (vs encryption, which is reversible) |
| **Healthcheck** | A periodic "are you actually working?" probe; Docker restarts containers that fail it |
| **Heartbeat** | A periodic "I'm alive + status" message from each player (every 30 s here) |
| **HMAC** | A keyed hash — only the key-holder can produce a valid signature (powers signed URLs) |
| **HTTP Range / 206** | "Give me only bytes X–Y of the file" — what makes video seeking work |
| **IndexedDB** | The browser's real database for large binary data — the player's media cache & log queue |
| **Interceptor** | A function that runs on *every* request/response (axios) — auth plumbing lives there |
| **IST** | Indian Standard Time (`Asia/Kolkata`) — the wall-clock all scheduling logic uses |
| **JPA / Hibernate** | Java's standard ORM API / the engine implementing it |
| **JSX** | The HTML-like syntax React components are written in |
| **JWT** | A signed token proving identity; the server verifies by math, storing no session |
| **Kiosk mode** | A device locked to one fullscreen app (the Android shell) |
| **Layered architecture** | Controller → Service → Repository → DB; each layer has one job |
| **localStorage** | The browser's small key-value store; survives restarts (tokens, device identity, config cache) |
| **Migration** | A versioned script that changes the DB schema (see Flyway) |
| **Monolith** | One deployable app containing all features (vs microservices) |
| **Multi-stage build** | A Dockerfile that compiles in a big image and ships only the small runtime image |
| **N+1 problem** | ORM trap: 1 query for a list + 1 per item; fixed with fetch-join / `@EntityGraph` |
| **nginx** | A fast web server used here as reverse proxy + static file server |
| **ORM** | Object-Relational Mapping — translator between objects and table rows |
| **Partial index** | An index/constraint applying only to rows matching a condition (`WHERE status='PENDING'`) |
| **Proof-of-play** | Signage-industry term: logged evidence that a creative actually played |
| **Publish/subscribe (pub-sub)** | Messaging pattern: subscribe to a topic, receive whatever is published to it |
| **Rate limiting** | Capping requests per client per time window (429 when exceeded) |
| **RBAC** | Role-Based Access Control — permissions attach to roles, not individuals |
| **Record (Java)** | A concise immutable data class — used for all DTOs |
| **Repository** | The data-access interface Spring Data implements for you |
| **REST** | HTTP API conventions: URLs are nouns, methods are verbs, requests are stateless |
| **Reverse proxy** | A front-door server that forwards each request to the right internal service |
| **Salting** | Mixing a random value into each password hash so identical passwords hash differently |
| **SHA-256** | A fast cryptographic hash — right for random tokens, wrong for passwords |
| **Signed URL** | A link carrying its own HMAC proof-of-permission + expiry |
| **Soft delete** | Marking a row deleted instead of removing it (media) |
| **SPA** | Single-Page Application — loads once, then JavaScript swaps the views |
| **STOMP** | A tiny protocol over WebSocket adding subscribe/send semantics |
| **Testcontainers** | Library that starts real services (Postgres) in Docker from inside tests |
| **Transaction** | A group of DB operations that all succeed or all roll back |
| **UTC** | The universal reference timezone; all *moments* are stored in it |
| **UUID** | A 128-bit random identifier — unguessable, collision-free primary keys |
| **Volume (Docker)** | Storage that outlives containers (the Postgres data) |
| **WebSocket** | A persistent two-way connection — the server can push without being asked |

---

## The decision cheat-sheet (one line per "why")

| Decision | Reason |
|---|---|
| Monolith backend | One team, one deploy, shared transactions; microservices = cost without benefit at this scale |
| PostgreSQL | The data is genuinely relational; transactions; partial indexes |
| Flyway + `ddl-auto: validate` | Schema as reviewed, versioned SQL; identical everywhere; no drift |
| JWT, stateless | No session store; any instance verifies by math; fits SPA + devices |
| Two tokens (30 min / 14 d, rotated) | Stolen access tokens age out fast; sessions still last comfortably |
| Separate device tokens for TVs | A screen is not a user — own header, own filter, own role |
| SHA-256 for device tokens, BCrypt for passwords | Random tokens can't be guessed (fast hash fine); human passwords can (slow hash required) |
| HMAC-signed media URLs | `<img>/<video>` can't send auth headers; leaked links self-destruct |
| STOMP topics | Address one screen or all portal tabs without inventing a protocol |
| In-memory broker & rate limiter | Single node by design; zero infra; documented upgrade path |
| Push tiny events + pull full config | Missed pushes self-heal; eventual consistency with trivial code |
| AFTER_COMMIT push | Never notify players about data that isn't durably saved yet |
| IndexedDB media cache + localStorage config | Playback and reboots survive with zero connectivity |
| Client-side schedule engine (IST) | Content switches on time even if the server is down at that moment |
| UTC moments + IST wall-clock rules | "6 PM in the store" stays 6 PM regardless of server/device timezone |
| Double-buffered playback | Broadcast-smooth transitions; no black flashes |
| Sequential downloads | Don't saturate a store's thin internet connection |
| Log queue, delete-after-ack | Proof-of-play (billing evidence) never lost — at-least-once |
| Heartbeat 30 s / offline 90 s | One lost packet ≠ false alarm; real outages noticed in ~1.5 min |
| Status events, not samples | 2 rows/day instead of 1,440; exact uptime math |
| Denormalized playback logs | Reports are immutable history — deletions can't rewrite the past |
| Soft-delete media | Playlists/caches/reports may still reference it |
| UUID storage filenames | Path traversal & collisions impossible by construction |
| ffmpeg optional + placeholders | A missing tool degrades quality, never breaks uploads |
| DTOs everywhere | Secrets can't leak; API shape decoupled from DB shape |
| One global error format | One backend handler + one frontend parser cover every failure |
| nginx same-origin proxy | One URL for everything; CORS complexity vanishes in production |
| nginx DNS re-resolve variable | Backend redeploys don't leave nginx proxying a dead IP (502s) |
| `try_files … /index.html` | Refreshing a TV on `/player` must return the app, not 404 |
| Backup sidecar (nightly, 14 d) | One corrupted disk ≠ losing every schedule and report |
| Healthcheck = real DB round-trip | "Healthy" means the stack works, not "process exists"; Docker auto-restarts |
| WebView Android app, zero deps | All logic stays server-updatable; ~1 MB APK runs on ₹2,500 boxes |
| BACK×5 hidden setup door | Kiosk must resist accidents but still admit technicians |
| Cleartext HTTP allowed on Android | Real deployments start as `http://LAN-IP` before TLS exists |
| Testcontainers integration tests | Real Postgres + real HTTP where mocks would lie |
| CI builds the APK as an artifact | Anyone gets an installable player build from any push |
| Multi-stage Docker + layer ordering | Small production images; code edits rebuild in seconds |
| Secrets fail-fast (`:?` in compose) | Refuse to boot without a real secret — never silently weak |

---

## Honest footnotes (small quirks found while reading the code)

- **Android error screen z-order:** the error view is added to the layout *before* the
  WebView, so the opaque WebView likely covers it — recovery (auto-retry) still works, but
  the "Cannot reach server" message may stay hidden behind the black WebView.
- **Android `onResume` reload check** treats a `null` WebView URL as "no change"; in
  practice the setup screen explicitly relaunching the main activity covers that path.
- **`date-fns`** is declared in `package.json` but never imported — a removable leftover.
- **No pagination** on screens/media lists (fetch-all + filter in the browser) — fine at
  this fleet size; the known first fix if the fleet grows 10×.
- **Single-node assumptions** (in-memory broker, in-memory rate limits, unguarded scheduled
  jobs) — deliberate and documented; revisit only when running multiple backend instances.
- The **weather widget** shows a hardcoded placeholder value, labeled as such — an honest
  stub, like the disabled integration cards on the Settings page.

---

*End of the guide. Start page: [../PROJECT_NOTES.md](../PROJECT_NOTES.md)*
