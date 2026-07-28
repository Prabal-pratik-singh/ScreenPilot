# Chapter 1 — Foundations (the concepts everything else is built on)

> If you already know what an API, JSON and a SPA are, skim the examples anyway — they all
> come from this project, so they double as a tour of the code.

---

## 1.1 Frontend, backend, database — the three-part split

Almost every serious web application is split into three parts. ScreenPilot follows this
exactly:

```
   FRONTEND                    BACKEND                       DATABASE
   (what you see)              (the brain)                   (the memory)

   React app running     →     Spring Boot server      →     PostgreSQL
   in YOUR browser             running on A SERVER           running on a server
   
   buttons, tables,            checks permissions,           stores users, screens,
   forms, maps, videos         applies business rules,       playlists, schedules,
                               reads/writes the DB           logs ... permanently
```

**Why split at all?** Three different jobs with three different needs:

- The **frontend** runs on untrusted machines (anyone's browser). It can be inspected and
  modified by the user — so it must never contain secrets or be trusted to enforce rules.
- The **backend** is the only trusted place. Every rule that matters ("viewers can't delete",
  "Ranchi manager sees only Ranchi") is enforced here, no matter what the frontend does.
- The **database** is the single source of truth. Only the backend talks to it — the browser
  never touches the database directly (that would let anyone read/change anything).

**In this repo:** `frontend/` is the React app, `src/main/java/` is the backend,
and PostgreSQL runs in Docker (`docker-compose.yml`).

---

## 1.2 What is an API? (the most important definition in this guide)

**API = Application Programming Interface.** It is the *menu of things one program allows
another program to ask it to do* — a contract: "if you send me *this* request, I will do
*this* and answer with *that*."

**Analogy — a restaurant:** you (the frontend) don't walk into the kitchen (the database) and
cook. You order from a **menu** (the API) via a **waiter** (HTTP). The kitchen (backend)
prepares the dish and the waiter brings it back. The menu tells you exactly what you may ask
for and what you'll get; how the kitchen works internally is hidden from you.

**Why use an API instead of letting the frontend touch the database?**

1. **Security** — the backend checks *who you are* and *what you're allowed to do* on every
   single request. The browser can't be trusted to do that.
2. **One brain, many clients** — the React portal, the TV player, the Android app, and even
   `curl` on a terminal all use the *same* API. Business rules live once, not four times.
3. **Independence** — the frontend team can redesign every page and the backend doesn't
   change; the backend can switch storage internals and the frontend doesn't notice.

**A real example from this project.** When the Screens page loads, the frontend makes this
API call:

```
Request:
  GET /api/screens
  Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...   ← proof of who you are (a JWT, see ch.7)

Response (HTTP 200 OK):
  [
    {
      "id": "0b6f3c9e-...",
      "name": "Ranchi Main Road — Entrance",
      "city": "Ranchi",
      "state": "Jharkhand",
      "status": "ONLINE",
      "lastHeartbeatAt": "2026-07-26T09:12:31Z",
      "currentItemName": "Summer Promo.mp4",
      ...
    },
    ...
  ]
```

The frontend never wrote SQL, never opened a database connection — it just asked the API.
On the backend side this request lands in `ScreenController.java` (the "waiter"), which calls
`ScreenService.java` (the "kitchen"), which uses `ScreenRepository.java` to fetch rows from
PostgreSQL and returns them converted to the JSON above.

---

## 1.3 What is HTTP? (the language of the web)

**HTTP (HyperText Transfer Protocol)** is the standard format for a request and a response
between two computers on the web. Every API call above is an HTTP call. An HTTP request has
four parts:

```
METHOD  URL                      ← what to do, and to what
Headers                          ← metadata: who am I, what format I accept, ...
Body (optional)                  ← the data I'm sending (e.g. the new playlist as JSON)
```

### HTTP methods — the "verbs"

| Method | Meaning | Real examples in ScreenPilot |
|---|---|---|
| **GET** | *Read* something. Never changes data. | `GET /api/screens` (list screens), `GET /api/reports/uptime?from=...&to=...` |
| **POST** | *Create* something new, or trigger an action. | `POST /api/media` (upload a file), `POST /api/auth/login`, `POST /api/screens/{id}/commands` (send RELOAD) |
| **PUT** | *Replace/update* something that exists. | `PUT /api/playlists/{id}/items` (save the reordered playlist) |
| **DELETE** | *Remove* something. | `DELETE /api/schedules/{id}` |

### HTTP status codes — the "answer types"

Every response carries a 3-digit code telling you how it went. The ones this project actually
uses:

| Code | Meaning | When ScreenPilot returns it |
|---|---|---|
| **200 OK** | Success | Normal successful call |
| **206 Partial Content** | "Here's just the piece of the file you asked for" | Video streaming — lets the player **seek** inside a video without downloading it all (see ch.3, HTTP Range) |
| **400 Bad Request** | You sent something invalid | `startTime must be HH:mm` when saving a schedule |
| **401 Unauthorized** | I don't know who you are | Missing/expired token; wrong password; unknown device token |
| **403 Forbidden** | I know who you are, but you may not do this | A VIEWER trying to create a screen; an invalid media-link signature |
| **404 Not Found** | That thing doesn't exist | `GET /api/playlists/<bad-id>` |
| **409 Conflict** | Valid request, but it clashes with current state | Duplicate email when inviting a user |
| **413 Payload Too Large** | File too big | Upload beyond the 500 MB limit |
| **429 Too Many Requests** | Slow down | More than 10 login attempts in a minute (rate limiting, ch.7) |
| **500 Internal Server Error** | The server crashed processing this | Any unexpected bug (the details are logged server-side, the client gets a generic message) |

**Why codes matter:** the frontend reacts to them mechanically — `401` triggers an automatic
token refresh, `403` shows "no permission", `429` means "try later". One number drives the
behavior; no parsing of English error text needed.

---

## 1.4 What is JSON? (the data format)

**JSON (JavaScript Object Notation)** is the text format APIs use to exchange structured
data. It's human-readable and every language can parse it. Just two building blocks:
objects `{ "key": value }` and arrays `[a, b, c]`.

```json
{
  "name": "Evening Offers",
  "allDay": false,
  "startTime": "18:00",
  "endTime": "22:00",
  "daysOfWeek": ["MON", "TUE", "WED", "THU", "FRI"],
  "screenIds": ["0b6f3c9e-...", "77aa21d0-..."]
}
```

That's literally the body the portal sends to `POST /api/schedules` when you publish the
"6 PM to 10 PM weekdays" schedule from the wizard.

**Why JSON and not XML or a binary format?** JavaScript (the browser) understands it
natively; it's compact enough; every tool can pretty-print it. In the backend, the **Jackson**
library (bundled with Spring Boot) converts Java objects ↔ JSON automatically — the config
`write-dates-as-timestamps: false` in `application.yml` makes dates come out as readable
ISO-8601 strings (`"2026-07-26T09:12:31Z"`) instead of huge numbers.

---

## 1.5 What is REST? (the style of API this project uses)

**REST (REpresentational State Transfer)** is a set of conventions for designing HTTP APIs.
The core ideas, in plain words:

1. **Everything is a resource with a URL.** Screens live at `/api/screens`, one screen at
   `/api/screens/{id}`, its commands at `/api/screens/{id}/commands`. URLs are *nouns*.
2. **The HTTP method is the verb.** You don't invent URLs like `/api/deleteScreen?id=7`;
   you send `DELETE /api/screens/7`.
3. **Stateless.** Every request carries everything needed to process it (your token, the
   parameters). The server keeps no memory of "your session" between requests — which is why
   any backend instance could answer any request. (This pairs with JWT — chapter 7.)

**ScreenPilot's REST API sketch** (the full list is in the README):

```
Auth       POST /api/auth/login · POST /api/auth/refresh · GET /api/auth/me
Screens    GET|POST /api/screens · PUT|DELETE /api/screens/{id}
           POST /api/screens/pair · POST /api/screens/{id}/commands
Media      POST|GET /api/media · GET /api/media/{id}/file · GET /api/media/{id}/thumb
Playlists  CRUD /api/playlists · PUT /api/playlists/{id}/items
Layouts    CRUD /api/layouts
Schedules  CRUD /api/schedules · POST /api/schedules/preview-conflicts
Reports    GET /api/reports/proof-of-play · GET /api/reports/uptime · GET /api/reports/export
Player     GET /api/player/config · POST /api/player/heartbeat · POST /api/player/logs
           POST /api/player/pair/request · GET /api/player/pair/poll/{code}
```

("**CRUD**" = **C**reate, **R**ead, **U**pdate, **D**elete — the four basic operations on data.)

**REST's limitation — and why this project adds WebSocket.** REST is strictly
*request → response*: the server can never speak first. But signage needs the server to say
"your schedule just changed!" to a TV *right now*. That's what **WebSocket** adds — a phone
line kept open in both directions (fully explained in chapter 3, §WebSocket).

```
REST  (walkie-talkie, client presses the button):        WebSocket (open phone line):

  Client ──── request ────▶ Server                       Client ◀────────────▶ Server
  Client ◀─── response ──── Server                       either side talks anytime
  ...silence until client asks again...
```

---

## 1.6 What is a SPA? (how the frontend is built)

A **SPA (Single-Page Application)** is a website that loads **once** and then rewrites the
page with JavaScript as you navigate — instead of asking the server for a whole new HTML page
per click.

**Old style (multi-page):** click "Screens" → browser requests `/screens.html` → whole page
reloads, white flash, everything re-downloads.

**SPA style (this project):** click "Screens" → **React Router** swaps the components on
screen instantly → only the *data* (`GET /api/screens`, small JSON) travels over the network.

**Why a SPA here?**
- The portal is a *tool*, not a document — drag-and-drop builders, live maps, modals, live
  status dots. That level of interactivity needs an app, not pages.
- The **player is necessarily a SPA**: it's one page that runs for days, managing downloads,
  timers, and playback. There is no "navigation" at all on a TV.
- One build serves both: the same compiled bundle answers `/` (portal) and `/player` (player).

**The one thing SPAs need from the server:** if a TV refreshes while on `/player`, the web
server must still return the app's `index.html` (there is no physical `/player` file on disk).
That's exactly what the nginx rule `try_files $uri /index.html;` does (chapter 6).

---

## 1.7 Monolith vs microservices (and why ScreenPilot is a monolith)

Two ways to build a backend:

```
MONOLITH (ScreenPilot)                    MICROSERVICES
┌────────────────────────────┐            ┌─────────┐ ┌─────────┐ ┌─────────┐
│  ONE deployable app        │            │ auth    │ │ media   │ │schedule │
│  auth + media + schedules  │            │ service │ │ service │ │ service │
│  + reports + websockets    │            └────┬────┘ └────┬────┘ └────┬────┘
│  ONE process, ONE database │                 │  network calls │       │
└────────────────────────────┘            ┌────▼───────────▼───────────▼────┐
                                          │   separate databases, queues,    │
                                          │   service discovery, tracing...  │
                                          └──────────────────────────────────┘
```

- A **monolith** is one application containing all the features, deployed as one unit.
- **Microservices** split each feature into its own small application, each independently
  deployed, talking to the others over the network.

Microservices exist to solve *organizational* scale (50 teams shipping independently) and
*extreme load* scale (scale only the hot service). They cost dearly: every function call
becomes a network call that can fail; transactions across services are hard; you need service
discovery, distributed tracing, more servers.

**ScreenPilot's choice: a modular monolith — and it's the right one.**
- One developer/team, one deploy, one database. A schedule save that touches schedules +
  screens + notifications happens in **one database transaction** — instantly consistent.
- The features genuinely share data (screens appear in schedules, reports, dashboards, maps).
- Scale target is hundreds of screens, not millions of users.
- The code still keeps clean internal boundaries — packages per concern, and **interfaces**
  like `StorageService` (disk today, S3 tomorrow) — so if one part ever needs to be split
  out, the seam already exists.

**Rule of thumb worth remembering:** start with a monolith; split only when you have a
concrete, painful reason. Complexity must be *earned* by real scale.

---

## 1.8 One more foundation: environments

The project runs in two modes, and knowing this explains many config files:

| | **Local development** | **Docker (production-style)** |
|---|---|---|
| Frontend | Vite dev server, `http://localhost:5174`, hot reload | Built once, served by nginx at `http://localhost:8090` |
| Backend | `./mvnw spring-boot:run`, port 8081 | Container `screenpilot-backend`, port 8081 |
| Database | Docker Postgres on port 5433 | Same container, internal network |
| Frontend→Backend | **Cross-origin** (5174 → 8081), allowed by CORS | **Same-origin** — nginx proxies `/api` internally |
| Config | `frontend/.env.development` sets `VITE_API_BASE_URL=http://localhost:8081` | Build arg left empty ⇒ app uses relative URLs |

**Why support both?** Dev mode gives instant hot-reload while coding; Docker mode proves the
real deployment shape (and is the one-command demo). Media uploads land in the same
`./uploads` folder in both modes (a Docker bind-mount), so switching modes doesn't lose files.

---

**Next:** [Chapter 2 — The architecture, with diagrams →](02-architecture.md)
