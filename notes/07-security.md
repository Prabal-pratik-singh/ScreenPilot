# Chapter 7 — Security, From First Principles

Security code is concentrated in `src/main/java/com/screenpilot/signage/security/` and
`config/SecurityConfig.java`. This chapter defines each concept simply, then shows how the
project applies it.

---

## 7.1 Two words you must not confuse: hashing vs encryption

| | **Hashing** | **Encryption** |
|---|---|---|
| Direction | **One-way** — you can never get the input back | **Two-way** — decryptable with the key |
| Question it answers | "Is this the same value I saw before?" | "Hide this, I'll need to read it later" |
| Example here | Passwords (BCrypt), device tokens (SHA-256) | (Not needed in this project) |

**Why passwords are hashed, never encrypted:** the server never needs to *read* your
password — only to check it matches. Store `hash(password)`; at login compute
`hash(attempt)` and compare. If the database leaks, attackers get hashes, not passwords.

**BCrypt — the password hasher used here.** Ordinary hashes (SHA-256) are *fast* — bad for
passwords, because attackers can try billions of guesses/second. **BCrypt is deliberately
slow** and salted:
- **Salted** = a random value is mixed into each hash, so two users with the same password
  get different hashes, and precomputed "rainbow tables" are useless.
- **Slow (tunable cost)** = each guess costs real CPU time, turning a billion-guess attack
  into centuries.

**Why the *device* tokens use fast SHA-256 instead (an important nuance):** a device token is
48 **random** bytes — there's no human-chosen, guessable password to brute-force, so
slowness adds nothing. What matters is only that the DB stores the hash, not the plaintext:

```
 TV sends:            X-Device-Token: kX92…KJ3   (the plaintext, over the network)
 server computes:     SHA-256("kX92…KJ3") = "e3a1…9c"
 server looks up:     SELECT * FROM screens WHERE device_token = 'e3a1…9c'
```

**Result:** someone who steals the entire database **cannot impersonate a single screen** —
they hold only hashes, and hashes don't reverse. (Migration `V7` retrofitted this without
breaking already-paired TVs — chapter 3 §Flyway.)

---

## 7.2 JWT — the login system, in detail

**What is a JWT (JSON Web Token)?** A string with three dot-separated parts:

```
   eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiI3ZjljIiwicm9sZSI6IkFETUlOIiwiZXhwIjoxNzg1... . 4kZ1Xf...
   └────── HEADER ─────┘  └──────────────── PAYLOAD (claims) ─────────────────┘  └─ SIGNATURE ┘
   "signed with HS256"     { sub: <user-id>, role: ADMIN, type: access,           HMAC-SHA256 of
                             email: …, exp: <expiry timestamp> }                  header+payload
                                                                                  using the
                                                                                  SERVER'S SECRET
```

- The payload is **readable by anyone** (it's just encoded, not encrypted) — so nothing
  secret goes in it. Its power is the **signature**: change one character of the payload and
  the signature no longer matches. Only the server knows the secret (`APP_JWT_SECRET`), so
  only the server can *mint* valid tokens — but it can *verify* them with pure math, **no
  database session table, no server-side memory**. That's what "stateless auth" means, and
  it's why REST + JWT pair so naturally (chapter 1 §REST: stateless).

**The two-token design and why:**

| Token | Lifetime | Can call APIs? | Purpose |
|---|---|---|---|
| **Access token** | 30 min | Yes — sent as `Authorization: Bearer …` on every request | Short-lived, so a stolen one is only briefly useful |
| **Refresh token** | 14 days | **No** — only good at `/api/auth/refresh` (the filter rejects `type: refresh` tokens on normal endpoints) | Lets sessions continue without re-typing passwords |

Refreshing also **rotates** the refresh token (you get a new one each time), and the whole
dance is invisible to users — the axios interceptor handles it (chapter 4 §axios).

```
 login ──▶ [access 30min] + [refresh 14d]
              │ 30 min pass… an API call returns 401
              ▼
 POST /api/auth/refresh (with refresh token)
              │
              ▼
 [NEW access] + [NEW refresh]  ── original request replayed, user noticed nothing
```

One more server-side detail: `JwtAuthFilter` re-reads the user (and their allowed groups)
from the DB on each request — so deactivating a user or changing their group access takes
effect **immediately**, not when their token expires.

---

## 7.3 The three-lane authentication model

Three different kinds of caller, three different credentials, one filter chain:

```
 request arrives
   │
   ├─ RateLimitFilter        (always first — see §7.5)
   │
   ├─ has  Authorization: Bearer <jwt>?   ──▶ JwtAuthFilter    → a USER (role + groups)
   ├─ has  X-Device-Token: <token>?       ──▶ DeviceTokenFilter → a DEVICE (ROLE_DEVICE)
   └─ neither                              ──▶ anonymous — only public endpoints work
```

And the URL rules (`SecurityConfig`, in order):

| URL pattern | Who may call it | Why |
|---|---|---|
| `/api/auth/**`, `/api/health` | anyone | You can't be logged in *before* logging in; health probes are unauthenticated |
| `/api/player/pair/**` | anyone | A brand-new TV has no credential yet — pairing is how it gets one (rate-limited instead) |
| `GET /api/media/*/file`, `/thumb`, `GET /api/screens/*/screenshot` | anyone **with a valid signature** | `<img>`/`<video>` tags can't send headers — see §7.4 |
| `/api/player/**` | `ROLE_DEVICE` only | Player endpoints are for paired TVs, never browsers |
| everything else | authenticated users, per-endpoint roles via `@PreAuthorize` | The portal API |

---

## 7.4 HMAC-signed URLs — protecting media without logins

**The problem:** the browser fetches images/videos with plain `<img src>` / `<video src>`
tags, and those **cannot attach an Authorization header**. Making media public would let
anyone crawl `/api/media/...`; requiring auth would break every thumbnail.

**The solution: capability URLs.** The API embeds a proof-of-permission *into the URL
itself*:

```
 /api/media/3f2a…/file?exp=1785612000&sig=9d41c6…

 exp = expiry (unix time, e.g. 12 hours from now)
 sig = HMAC-SHA256( "media:3f2a…|1785612000" , derived-signing-key )
```

**What is HMAC?** A keyed hash: only someone holding the key can compute the correct `sig`
for given content. The server verifies in microseconds — no DB lookup — and rejects if:
the URL was **tampered** (any change to id or exp breaks the signature), or the time has
**expired** (a leaked link dies on its own).

Careful details in `UrlSigner.java` worth naming:
- The signing key is **derived** from the app secret (`HMAC(secret, "screenpilot-url-signing")`)
  so JWT-signing and URL-signing never share raw key material.
- Comparison uses `MessageDigest.isEqual` — **constant-time**, immune to timing attacks
  (comparing strings char-by-char leaks *how many* leading characters matched).
- Resource IDs are namespaced (`media:<id>` vs `screenshot:<id>`) — a media signature can't
  unlock a screenshot.

| Link type | Validity | Reasoning |
|---|---|---|
| Media file/thumb in portal lists | 12 h | A browsing session's lifetime |
| Media URLs inside player config | 24 h | Players refetch config often; generous is fine |
| "Now playing" thumbnail | 1 h | Refreshed with every heartbeat anyway |
| Screenshot link | 15 min | The most sensitive (shows live screen content) |

---

## 7.5 Rate limiting — the bouncer

**What it is:** capping how often one client may hit an endpoint. **Why:** two endpoints are
brute-forceable by nature — login (guess passwords) and pairing (guess 6-character codes).

`RateLimitFilter` runs **before everything** and keeps, per client IP, a sliding 60-second
window of request timestamps (a `ConcurrentHashMap` — in-memory, honest single-node choice):

| Endpoint | Limit | Attack blocked |
|---|---|---|
| `POST /api/auth/login` | 10/min | Password guessing |
| `POST /api/auth/refresh` | 30/min | Token-mill abuse |
| `POST /api/player/pair/request` | 10/min | Code-pool flooding |
| `GET /api/player/pair/poll/*` | 100/min | Code guessing (while allowing legit 3s polling) |

Over the limit → **HTTP 429**. Detail: the client IP is read from the `X-Forwarded-For`
header, because behind nginx every request's direct peer *is* nginx — the real caller's IP
travels in that header (which nginx sets — chapter 6).

---

## 7.6 CORS and CSRF — explained simply, and why this project's settings are safe

**CORS (Cross-Origin Resource Sharing):** browsers block JavaScript on site A from calling
site B's API unless B explicitly allows it. In dev, the portal (`localhost:5174`) calls the
backend (`localhost:8081`) — different ports = different origins — so the backend must allow
it. In Docker, nginx makes everything **same-origin** and CORS never triggers.

**Why `allowed-origins: "*"` (allow everyone) is OK *here*:** CORS mainly protects
cookie-based sites, because browsers attach cookies *automatically* to cross-site requests.
ScreenPilot's auth rides in **explicit headers** read from localStorage — a malicious site
cannot read another origin's localStorage, so it cannot forge an authenticated call. The
permissive default also keeps tunnels, LAN IPs and custom domains working without config.

**CSRF (Cross-Site Request Forgery)** — the attack where `evil.com` makes your browser POST
to `bank.com` *with your cookies attached*. Disabled here (`csrf.disable()`) for the same
reason: **no cookies, nothing to forge with.** These two settings look scary until you see
the auth model; then they're exactly right — and that's why the config comments them.

---

## 7.7 RBAC + group scoping — who may do and see what

**RBAC (Role-Based Access Control):** permissions attach to roles, users get roles.

```
 SUPER_ADMIN  >  ADMIN  >  CONTENT_MANAGER  >  VIEWER      (a HIERARCHY: each role
                                                            includes everything below)
 manage users    manage screens,   upload media,            view dashboards,
 (and all below) groups, pairing,  build playlists/         screens, reports
                 remote commands   layouts/schedules
```

The hierarchy is declared **once** as a Spring `RoleHierarchy` bean, so an endpoint marked
`@PreAuthorize("hasRole('VIEWER')")` automatically admits every higher role — no
`hasAnyRole('VIEWER','ADMIN',…)` repetition to forget somewhere.

**Group scoping — the second dimension.** Roles say what you can *do*; groups say what you
can *see*. A user restricted to the "Ranchi" group gets every list filtered: screens,
schedules, dashboard tree, map, reports. The filter lives in **one** method
(`ScreenService.accessibleScreens()`) that everything calls — and the reports service
*intersects* any requested screen ids with the accessible set, so even hand-crafted API
calls naming foreign screens return nothing (fail-closed, silently).

The frontend mirrors the model (`ROLE_RANK` in `AuthContext.jsx`) to hide buttons/routes —
but that's UX politeness. The backend check is the law; the integration tests prove both the
floor and the ceiling of each role.

---

## 7.8 Upload and output hardening (the quiet defenses)

**Uploads** (`MediaService`):
- **Extension allow-list** (`mp4, webm, jpg, jpeg, png, webp, pdf`) — the type is derived
  from the extension, never from the client's claimed MIME type (trivially fakeable).
- **Stored under a server-generated UUID filename** — the user's filename never touches the
  filesystem. This kills **path traversal** by construction: a file uploaded as
  `../../etc/cron.d/evil.jpg` is simply stored as `uploads/media/3f2a….jpg` — the malicious
  name is never used as a path, so there is nothing to traverse. `LocalDiskStorageService`
  *additionally* verifies every resolved path stays inside the storage root (defense in depth).
- 500 MB size cap, enforced in both Spring config and the service.

**Outputs:**
- The PDF exporter **HTML-escapes** every user string. Screen names are user input rendered
  into an HTML template — without escaping, naming a screen `<img src=x onerror=…>` would be
  an injection into the PDF renderer.
- Media downloads sanitize the filename in the `Content-Disposition` header (strips
  `\r`, `\n`, `"`) — blocking **header injection** through crafted asset names.

**Secrets:** the JWT secret must be ≥32 chars (startup fails otherwise); Docker refuses to
start without it; bare dev runs generate an ephemeral one with a loud warning. Fail fast and
loud, never silently weak.

---

## 7.9 The security model in one table

| Threat | Defense | Where |
|---|---|---|
| Stolen password database | BCrypt (slow, salted) | `UserService` / seeders |
| Stolen device-token database | Only SHA-256 hashes stored | `TokenHasher`, `DeviceTokenFilter`, V7 |
| Stolen/old media links | HMAC signature + expiry | `UrlSigner`, `MediaController` |
| Tampered links | Signature covers id+expiry; constant-time compare | `UrlSigner.verify` |
| Password / pairing-code guessing | Per-IP sliding-window rate limits → 429 | `RateLimitFilter` |
| Token theft impact | 30-min access tokens; rotation on refresh | `JwtService`, `AuthService` |
| Deactivated user still acting | User re-loaded from DB every request | `JwtAuthFilter` |
| Malicious uploads / path traversal | Extension allow-list + UUID filenames + root check | `MediaService`, `LocalDiskStorageService` |
| Injection via names into PDF/headers | HTML-escaping; header sanitizing | `ExportService`, `MediaController` |
| Cross-site request forgery | No cookies — header tokens only (CSRF off *because of* that) | `SecurityConfig` |
| Brute-forcing the API for data of other branches | Group scoping intersected server-side | `ScreenService`, `ReportService` |
| Weak/missing secret in production | Compose refuses to start; length validated | `docker-compose.yml`, `SecretProvider` |

---

**Next:** [Chapter 8 — The database design →](08-database.md)
