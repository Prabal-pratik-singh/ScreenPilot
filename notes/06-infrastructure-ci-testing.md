# Chapter 6 — Infrastructure: Docker, nginx, Backups, CI & Testing

This chapter covers everything *around* the code: how it's packaged, served, backed up,
built and tested automatically.

---

## 6.1 What is Docker? (definitions first)

**The problem Docker solves:** "it works on my machine." Your app needs Java 17, ffmpeg,
specific env vars, a Postgres at a certain address… reproducing that by hand on every
machine is error-prone.

**The three words to know:**

| Term | What it is | Analogy |
|---|---|---|
| **Image** | A frozen, layered snapshot of a filesystem + a start command (e.g. "Ubuntu + Java 17 + ffmpeg + app.jar, run `java -jar app.jar`") | A recipe, frozen |
| **Container** | A running instance of an image, isolated from the host | A dish cooked from the recipe |
| **Volume / bind-mount** | Storage that lives *outside* the container so data survives container restarts/rebuilds | The pantry — the kitchen can be rebuilt, the food keeps |

**Why this project uses Docker:** one command — `docker compose up -d --build` — gives anyone
(reviewer, new laptop, a real server) the entire working platform: database, backend with
ffmpeg, frontend with nginx, backups. Zero "install Java, install Node, install Postgres…"

---

## 6.2 The backend's Dockerfile — a multi-stage build

`Dockerfile` (repo root) has **two stages**:

```dockerfile
# STAGE 1 — "build": has Maven + JDK (big, ~700MB), compiles the jar
FROM maven:3.9-eclipse-temurin-17 AS build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline    # ← download dependencies FIRST (see below)
COPY src ./src
RUN mvn -q -B package -DskipTests      # tests need Docker themselves — CI runs them

# STAGE 2 — "runtime": only a JRE + the jar (much smaller, no compiler ships to prod)
FROM eclipse-temurin:17-jre
RUN apt-get install -y ffmpeg curl     # ffmpeg → video thumbnails; curl → healthcheck
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java","-jar","app.jar"]
```

**Two tricks worth understanding:**

1. **Multi-stage:** the final image contains *only* what's needed to run — no Maven, no
   source code, no JDK compiler. Smaller, faster to ship, smaller attack surface.
2. **Layer-cache ordering:** Docker caches each step and reuses it if its inputs didn't
   change. By copying `pom.xml` and downloading dependencies **before** copying `src/`,
   editing your code doesn't invalidate the dependency layer — rebuilds take seconds instead
   of re-downloading the internet. (The frontend Dockerfile plays the same trick with
   `package.json` + `npm ci` before copying source.)

---

## 6.3 docker-compose — the four services

**What is docker-compose?** A YAML file describing *several* containers, their network, their
startup order and health rules — the whole system as one declarative file.

```
docker-compose.yml
├── postgres   postgres:16-alpine        port 5433 → 5432
│              data in the named volume `signage_pgdata` (survives everything)
│              healthcheck: pg_isready
├── backend    built from ./Dockerfile   port 8081
│              waits for postgres to be HEALTHY (not just started)
│              ./uploads bind-mounted → media survives, shared with local dev
│              healthcheck: curl /api/health  (a REAL app+DB round-trip;
│              Docker auto-restarts the container if it hangs)
│              REFUSES to start without APP_JWT_SECRET (see §6.6)
├── frontend   built from ./frontend     port 8090 → 80
│              nginx serving the built app + proxying /api and /ws
│              waits for backend to be healthy
└── backup     postgres:16-alpine        (no ports)
               a loop: nightly `pg_dump | gzip` → ./backups/signage-<date>.sql.gz
               deletes backups older than 14 days
```

**Details that show operational care:**
- **Healthchecks + `depends_on: condition: service_healthy`** — the backend doesn't boot
  until Postgres actually accepts connections; nginx doesn't boot until the backend answers.
  No crash-loop roulette at startup.
- The backend healthcheck endpoint (`/api/health`) performs a **database query** — so
  "healthy" means the *whole stack* works, not merely "the process exists". Docker's
  `restart: unless-stopped` + failing healthchecks = self-healing restarts.
- **The backup sidecar** is the cheapest insurance in the whole repo: one corrupted disk no
  longer means losing every schedule and report. Restoring = `gunzip < backup.sql.gz | psql`.

---

## 6.4 nginx — the reverse proxy (and why it matters so much)

**What is nginx?** A small, extremely fast web server. **What is a reverse proxy?** A server
that receives all incoming traffic and forwards each request to the right internal service —
the "front desk" of the system.

```
                    browser / TV  →  http://your-host:8090
                                      │
                            ┌─────────▼─────────┐
                            │       nginx        │
                            └─┬───────┬───────┬──┘
              path starts     │       │       │
              with /api/  ────┘       │       └────  everything else
                 │                    │                  │
                 ▼               path /ws                ▼
          backend:8081                │            serve the built React files;
          (REST API)                  ▼            unknown paths → index.html
                              backend:8081         (SPA fallback: try_files)
                              with WebSocket
                              upgrade headers
```

**Why put nginx in front at all? The same-origin superpower.** Because the portal, the
player, `/api` and `/ws` all live on **one URL (port 8090)**:
- No CORS complexity in production (chapter 7 §CORS).
- A TV needs exactly **one address** typed into it.
- The production React build uses *relative* URLs (`VITE_API_BASE_URL` is empty).

**Three non-obvious lines in `frontend/nginx.conf`, each preventing a real failure:**

1. `resolver 127.0.0.11 valid=10s;` + `set $backend_upstream http://backend:8081;` +
   `proxy_pass $backend_upstream;` — using a **variable** forces nginx to re-resolve the
   backend's IP through Docker's DNS every 10 s. Without this, nginx caches the backend
   container's IP at startup **forever** — redeploy the backend (new IP) and nginx serves
   502 errors until *nginx itself* is restarted. A classic Docker+nginx trap, dodged.
2. The `/ws` block adds `Upgrade`/`Connection "upgrade"` headers (proxies must explicitly
   pass the WebSocket handshake) and `proxy_read_timeout 3600s` — otherwise nginx's default
   60 s idle timeout would silently kill every player's socket, causing reconnect storms.
3. `try_files $uri /index.html;` — the **SPA fallback** (chapter 1): a TV refreshing on
   `/player` must receive the app, not a 404. Plus `client_max_body_size 600m` so 500 MB
   uploads aren't rejected by the proxy before reaching Spring.

---

## 6.5 Ports cheat-sheet

| Port | What | Mode |
|---|---|---|
| **8090** | nginx: portal + `/player` + `/api` + `/ws` — the one URL | Docker |
| **8081** | Spring Boot backend (direct) | both |
| **5174** | Vite dev server (portal with hot reload) | local dev |
| **5433** | PostgreSQL (host side; 5432 inside the network) | both |

---

## 6.6 Secrets & configuration — the `.env` contract

**Environment variables** are the standard way to give containers their config without
baking secrets into images or git.

- `.env.example` (committed) documents what's needed; you copy it to `.env` (git-ignored).
- **`APP_JWT_SECRET`** — signs login tokens *and* media links (min 32 chars). The compose
  file uses `${APP_JWT_SECRET:?...}` syntax: **the backend refuses to start without it** —
  fail loudly at deploy time beats silently running with a weak secret. (A bare local
  `./mvnw` run generates a random throwaway secret and warns you — sessions die on restart,
  a deliberate nudge.)
- **`POSTGRES_PASSWORD`** — defaults to `signage` for dev; the example file even documents
  the extra `ALTER USER` command needed if you change it after first start (because the DB
  volume already stored the old one).

---

## 6.7 What is CI? — GitHub Actions

**CI (Continuous Integration):** every push runs an automated pipeline that builds and tests
everything on a fresh machine. Broken code is caught in minutes — not on demo day. The
pipeline is code too: `.github/workflows/ci.yml`.

```
                        git push
                           │
        ┌──────────────────┼──────────────────────┐
        ▼                  ▼                      ▼         (3 jobs, in parallel)
 ┌─────────────┐   ┌──────────────┐   ┌────────────────────────┐
 │ BACKEND      │   │ FRONTEND     │   │ ANDROID                │
 │ ./mvnw verify│   │ npm ci       │   │ ./gradlew assembleDebug│
 │ = compile +  │   │ npm run build│   │ + upload the APK as    │
 │ integration  │   │ (build must  │   │ artifact               │
 │ tests against│   │  succeed)    │   │ `screenpilot-player-   │
 │ real Postgres│   │              │   │  apk` (kept 30 days)   │
 └─────────────┘   └──────────────┘   └────────────────────────┘
```

**The clever third job:** anyone can download an installable Android APK from the Actions
tab of any push — no Android Studio required. CI is not just a guard; it's the **release
factory**.

**Two tiny files that keep CI green (easy to miss, painful to debug):**
- `.gitattributes` pins `mvnw` to **LF** line endings — a Windows checkout converting it to
  CRLF would break Linux CI with the cryptic `bad interpreter: ^M`.
- CI runs `chmod +x mvnw` / `chmod +x gradlew` because Git on Windows doesn't record the
  executable bit.

---

## 6.8 Testing strategy — one honest integration suite

`src/test/java/.../SignageIntegrationTests.java` is the whole test suite, and its philosophy
is deliberate: **no mocks — test the real thing.**

**How it works:**
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` boots the actual application.
- **Testcontainers** starts a throwaway `postgres:16-alpine` container;
  `@ServiceConnection` wires it in automatically. The real Flyway migrations (V1–V7) and the
  real seeders run against it — so the migrations and seed data are *themselves under test*.
- Tests then drive **real HTTP** requests (`TestRestTemplate`) against the running app.

**What it protects (the crown-jewel flows):**

| Test | What it proves |
|---|---|
| wrong password → 401 | Auth rejects bad credentials |
| admin sees ≥12 seeded screens | Seeder + listing work |
| viewer: GET 200, POST 403, /users 403 | The role hierarchy has both a floor and a ceiling |
| Ranchi manager sees only Jharkhand screens | **Group scoping** actually filters data |
| full pairing flow, then a garbage token → 401 | The handshake works end-to-end *and* lookups go through the SHA-256 hash |
| media file: unsigned → 403, signed → 200, tampered signature → 403 | Signed URLs enforce access, and tampering is detected |
| all-day over all-day = conflict; timed over all-day = no conflict | The schedule layering rule is encoded in tests |

**Why integration tests here instead of unit tests?** Every one of these behaviors emerges
from *multiple layers cooperating* (security filters + SQL + services + JSON). Mock-based
unit tests would happily pass while the real wiring was broken. For this codebase's risk
profile, seven honest end-to-end tests beat seventy mocked ones.

**A pragmatic Windows note** (from the test's own javadoc): on this dev machine Testcontainers
can't reach the Docker daemon (a known Windows named-pipe incompatibility), so the suite is
marked `disabledWithoutDocker = true` — it **skips locally and enforces in CI**, where Linux
runners have working Docker. Tests that can't run are skipped loudly, not deleted quietly.

---

**Next:** [Chapter 7 — Security, from first principles →](07-security.md)
