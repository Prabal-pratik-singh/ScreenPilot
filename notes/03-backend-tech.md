# Chapter 3 — Backend Technologies (each one: what it is, why it's here, real example)

The backend lives in `src/main/java/com/screenpilot/signage/` and is declared in `pom.xml`.
This chapter walks through every technology in it.

---

## 3.1 Java 17

**What it is:** a general-purpose, strongly-typed programming language that runs on the
**JVM (Java Virtual Machine)** — meaning the same compiled program runs on Windows, Linux
or macOS unchanged.

**Why this project uses it:**
- **LTS (Long-Term Support)** version — stable for years, supported by every library.
- **Strong typing** catches whole categories of bugs at compile time — valuable in a system
  with many interconnected models (screens ↔ schedules ↔ playlists ↔ media).
- **Records** (a Java 17-era feature) make DTOs one-liners. Example from `dto/ScreenDtos.java`
  style code:

```java
// A record: an immutable data class — fields, constructor, equals/hashCode for free.
public record LoginRequest(String email, String password) {}
```

---

## 3.2 Spring Boot 3.3.4 — the framework

**What is a framework?** A library is code *you call*; a framework is code that *calls you* —
it runs the show (starts the web server, routes requests, opens DB connections) and you plug
your logic into well-defined slots. **Spring Boot** is Java's dominant application framework.

**The one concept you must know — Dependency Injection (DI):**
Instead of every class building the things it needs (`new ScreenRepository(...)` everywhere),
you *declare* what you need in the constructor, and Spring constructs everything once, wires
it together, and hands each class its dependencies. The constructed objects are called
**beans**, living in Spring's **application context**.

```java
@Service                                   // "Spring, manage this class as a bean"
public class ScreenService {
    private final ScreenRepository screens;   // "I need a ScreenRepository"
    public ScreenService(ScreenRepository screens) {   // Spring injects it — nobody
        this.screens = screens;                        // ever writes 'new' here
    }
}
```

**Why DI matters:** swap implementations without touching users (tests inject a fake; the
`StorageService` interface can get an S3 implementation), and object lifecycles/config live
in one place.

**Why Spring Boot specifically:**
- "**Starters**" bundle everything for a concern: this project pulls `starter-web` (REST),
  `starter-security`, `starter-data-jpa`, `starter-websocket`, `starter-validation` — five
  lines in `pom.xml` and the hard wiring is done, with production-grade defaults.
- **Embedded web server** (Tomcat) — the app is a single runnable `.jar`; no separate server
  install. That's what makes the Docker image trivial: `java -jar app.jar`.
- **Configuration as data** — `application.yml` + environment variables. This project binds
  them to a typed class `AppProperties` (`@ConfigurationProperties("app")`), so config like
  `app.player.offline-after-seconds: 90` arrives as a typed Java field, not magic strings.

**Annotations you'll see constantly (a cheat sheet):**

| Annotation | Means |
|---|---|
| `@RestController` | This class handles HTTP requests and returns JSON |
| `@GetMapping("/api/screens")` | This method answers `GET /api/screens` |
| `@Service` | Business-logic bean |
| `@Transactional` | Everything in this method is one database **transaction** — all of it commits or none of it does (e.g. saving a schedule + its target screens can never half-succeed) |
| `@Scheduled(fixedDelay = 15000)` | Run this method every 15 s in the background (the offline sweeper) |
| `@PreAuthorize("hasRole('ADMIN')")` | Reject the call before the method body runs unless the caller has the role |
| `@Valid` | Validate the incoming JSON against the annotations on the DTO |

---

## 3.3 Spring Data JPA + Hibernate — talking to the database

**What is an ORM?** **Object-Relational Mapping**: a translator between *objects* (how Java
thinks: `screen.getName()`) and *relational tables* (how databases think: rows and columns).
**JPA** is Java's standard API for ORM; **Hibernate** is the engine implementing it;
**Spring Data JPA** adds the repository layer on top.

**Step 1 — an entity** (a class mapped to a table), from `domain/Screen.java` (simplified):

```java
@Entity
@Table(name = "screens")
public class Screen {
    @Id
    private UUID id = UUID.randomUUID();   // primary key, assigned in Java

    private String name;                    // → column "name"
    @Enumerated(EnumType.STRING)
    private Status status;                  // ONLINE / OFFLINE stored as text

    @ManyToOne(fetch = FetchType.EAGER)     // many screens belong to one group
    private ScreenGroup group;

    private Instant lastHeartbeatAt;        // live telemetry, updated every heartbeat
    private String deviceToken;             // ⚠ stores the SHA-256 HASH, never the token
    ...
}
```

**Step 2 — a repository** (you write the interface, Spring writes the implementation):

```java
public interface ScreenRepository extends JpaRepository<Screen, UUID> {
    // Derived query: Spring parses the METHOD NAME and generates the SQL.
    Optional<Screen> findByDeviceToken(String tokenHash);

    // Used by the offline sweeper: "online screens whose last heartbeat is too old"
    List<Screen> findOnlineWithHeartbeatBefore(Instant cutoff);
}
```

Calling `screens.findByDeviceToken(hash)` runs
`SELECT * FROM screens WHERE device_token = ?` — you never wrote SQL, and the parameter is
safely bound (no SQL-injection risk).

**Why an ORM?** 90% of data access is mechanical CRUD; the ORM erases that boilerplate and
keeps queries type-checked against the entities. The remaining 10% (reports aggregation) is
still done in explicit code where it's clearer.

**Two sharp edges the project handles deliberately:**

- **The N+1 problem.** Load 50 schedules, then touch `schedule.getScreens()` on each → 1 + 50
  queries. Fix: **`@EntityGraph`** / `join fetch` queries like
  `ScheduleRepository.findWithScreensById(...)` that fetch a schedule *and* its screens and
  playlist in **one** SQL statement.
- **`open-in-view: false`** (`application.yml`). By default Spring keeps the DB session open
  while JSON is being written, letting lazy relations load "accidentally" — hiding N+1s.
  Turning it off forces every fetch plan to be explicit and intentional.
- **`ddl-auto: validate`** — Hibernate is *not allowed* to alter the schema; it only checks
  that entities match it. Schema changes go through Flyway (next section), reviewed as SQL.

---

## 3.4 PostgreSQL 16 — the database

**What it is:** a **relational database** — data in tables with columns, rows, and enforced
relationships (**foreign keys**), queried with **SQL**. Free, open-source, and famously
reliable ("the world's most advanced open-source database").

**Why relational (and not MongoDB or similar)?** Look at the data: users ↔ groups ↔ screens ↔
schedules ↔ playlists ↔ media — it *is* a web of relationships, and the questions asked of it
are relational ("all screens in group X with an active schedule containing media Y").
Foreign keys make dangling references impossible; **transactions** make multi-table updates
atomic.

**Postgres-specific features the project actually uses:**
- A **partial unique index**: `CREATE UNIQUE INDEX ... ON pairing_codes(code) WHERE
  status='PENDING'` — a pairing code must be unique *only while claimable*; old expired rows
  can keep their codes. A plain unique constraint couldn't express that.
- The `pgcrypto` extension (in migration V6) for bcrypt hashing inside SQL.
- Plain **indexes** on every hot lookup: `(screen_id, started_at)` on playback logs (range
  scans for reports), `(state, city)` on screens (the location tree), etc.

The Java driver `org.postgresql:postgresql` is the bridge (JDBC driver).

---

## 3.5 Flyway — database migrations

**What is a migration?** A versioned, ordered SQL script that changes the schema. Flyway
runs, on every startup, exactly the scripts that haven't run yet (tracked in a
`flyway_schema_history` table), in order:

```
src/main/resources/db/migration/
  V1__phase1_core.sql              users, groups, screens, pairing codes
  V2__phase2_media_playlists.sql   media_assets, playlists, playlist_items
  V3__phase3_scheduling.sql        schedules, schedule_targets, playback_logs
  V4__phase4_layouts.sql           layouts, layout_zones
  V5__phase5_commands_uptime.sql   screen_commands, screen_status_events
  V6__rebrand_screenpilot.sql      data-only rebrand of seeded rows
  V7__hash_device_tokens.sql       replace stored device tokens with SHA-256 hashes
```

**Why migrations instead of "Hibernate, auto-create my tables"?**
1. **Every environment builds the identical schema** — dev laptop, Docker, CI's throwaway
   test DB. No drift, no "works on my machine".
2. **Schema changes are code-reviewed SQL**, with comments explaining intent.
3. **Safe upgrades of live data.** V7 is the showcase: it adds token hashing *without
   breaking already-paired TVs* — it back-fills `device_token = sha256(device_token)` for
   old rows (skipping already-hashed ones by checking length), while devices keep sending
   plaintext and the server hashes before comparing. A zero-downtime security upgrade,
   expressible only because migrations are real SQL scripts.

---

## 3.6 JWT + jjwt — stateless login tokens (overview; deep-dive in chapter 7)

**What is a JWT (JSON Web Token)?** A signed string proving who you are:
`header.payload.signature`, where the payload contains claims (user id, role, expiry) and the
signature — computed with the server's secret — makes it tamper-proof. The server doesn't
store sessions; it just *verifies the math* on each request. The `jjwt` library does the
encoding/verification.

**In this project:** login returns an **access token** (30 min, sent on every request) and a
**refresh token** (14 days, only good for getting new tokens). Full mechanics, and why this
design, in chapter 7.

---

## 3.7 WebSocket + STOMP — the real-time channel

**What is WebSocket?** Normal HTTP is request→response, then the connection is done — the
server can never speak first. A **WebSocket** starts as an HTTP request that then *upgrades*
into a persistent two-way pipe: either side can send a message at any time, for hours.
Perfect for "the server must notify the TV *now*".

**What is STOMP and why add it on top?** Raw WebSocket is just a pipe of bytes — no notion of
"subscribe to X". **STOMP (Simple Text Oriented Messaging Protocol)** adds tiny structured
frames: `SUBSCRIBE /topic/screen/42`, `SEND /app/player/heartbeat`. That gives you
**publish/subscribe** semantics without inventing your own protocol, and Spring +
`@stomp/stompjs` (frontend) both speak it natively.

**The wiring** (`config/WebSocketConfig.java`):

```java
registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS(); // fallback flavor
registry.addEndpoint("/ws").setAllowedOriginPatterns("*");              // plain WebSocket
config.enableSimpleBroker("/topic", "/queue");   // in-memory broker
config.setApplicationDestinationPrefixes("/app"); // client→server messages
```

- Registered **twice** so both older SockJS clients and modern raw-WebSocket clients work.
- The **simple broker** is in-memory: subscriptions live inside this one JVM — zero extra
  infrastructure, with the single-node constraint noted in chapter 2.

**Sending a push** is one line via `ScreenEventPublisher`:

```java
messagingTemplate.convertAndSend("/topic/screen/" + screenId,
        Map.of("type", "SCHEDULES_UPDATED"));
```

**A subtle correctness detail worth knowing:** playlist/layout change notifications are sent
from a `@TransactionalEventListener(phase = AFTER_COMMIT)` — meaning the push happens only
*after* the database transaction has definitely committed. Otherwise a fast player could
refetch its config *before* the new data was durable and see the old version (or act on a
transaction that later rolls back).

---

## 3.8 Bean Validation — checking input at the door

**What it is:** annotations on DTO fields (`@NotBlank`, `@Email`, `@Size`) that Spring checks
automatically when a controller parameter is marked `@Valid`. Invalid input never reaches
business code; the client gets `400` with per-field messages inside the standard error JSON
(`fieldErrors`).

**Why:** validation written as annotations is visible in one glance at the DTO, and the
error format stays uniform because `GlobalExceptionHandler` converts the failure like every
other error.

---

## 3.9 The media pipeline — Thumbnailator, PDFBox, ffmpeg/ffprobe

Uploading a file kicks off a small factory line (`media/MediaService.java` +
`media/MediaProbeService.java`):

```
 file arrives (multipart POST /api/media)
   │
   ▼
 checks: not empty · ≤ 500 MB · extension in allow-list
         (mp4, webm → VIDEO · jpg, jpeg, png, webp → IMAGE · pdf → PDF)
   │
   ▼
 stored on disk as  uploads/media/<UUID>.<ext>     ← the user's filename is NEVER
   │                                                  used on disk (safety, ch.7)
   ▼
 metadata + thumbnail, by type:
   IMAGE ──▶ Thumbnailator: resize to 480px wide JPEG; record real width/height
   VIDEO ──▶ ffprobe: read duration + resolution
        └──▶ ffmpeg: grab a frame at 0.5s → 480px thumbnail
   PDF   ──▶ PDFBox: render page 1 at 72 DPI → Thumbnailator → thumbnail
   │
   ▼  (ANY failure above → log a warning, use a branded placeholder — upload still succeeds)
 row saved in media_assets; response includes signed fileUrl + thumbUrl
```

**The pieces:**
- **Thumbnailator** — a tiny image-resizing library; one fluent call replaces pages of
  `Graphics2D` math.
- **Apache PDFBox** — the standard Java PDF library; here it just renders page 1 as an image.
- **ffmpeg / ffprobe** — *not Java libraries*: the industry-standard command-line video tools,
  executed as external processes via `ProcessBuilder`. Reading "duration and resolution of an
  arbitrary mp4" in pure Java is a swamp; ffmpeg does it perfectly.

**The design decision to remember: ffmpeg is optional.** At startup the service probes
whether `ffmpeg -version` works and remembers the answer. Without it, uploads still succeed
and get a generated navy-and-marigold placeholder thumbnail (drawn with Java2D). The Docker
image installs ffmpeg, so in Docker everything just works; a bare laptop without ffmpeg
degrades gracefully instead of erroring. *A missing convenience tool must never break a core
feature.*

**Serving video — HTTP Range.** The media endpoint returns Spring `Resource` objects, and
`WebConfig` registers a `ResourceRegionHttpMessageConverter`: when a player asks for
`Range: bytes=1000000-2000000` (jumping to the middle of a video), Spring answers
`206 Partial Content` with just that slice — this is what makes video **seeking** work.

---

## 3.10 Report exports — Apache POI + openhtmltopdf

Two libraries turn the on-screen reports into downloadable files (`service/ExportService.java`):

- **Apache POI** — the Java library for Microsoft Office formats. Builds the **.xlsx**
  exports cell by cell: a navy brand banner row, marigold header row, then data; the uptime
  sheet generates one column per day. (Detail: column autosizing is capped to the first 12
  columns because autosize is computationally expensive on wide sheets.)
- **openhtmltopdf** — renders HTML+CSS into a **PDF**. The report is written as an HTML
  template (A4 landscape, brand colors, green/amber/red cells by threshold) — far easier to
  design than drawing PDF primitives by hand. Every user-provided string (screen names!) is
  HTML-escaped before insertion — a user could otherwise name a screen
  `<script>...</script>` and inject markup into the PDF (chapter 7).

**Why both formats?** Excel for people who want to *analyze* (pivot, filter); PDF for people
who want to *send* (branded, uneditable, prints cleanly).

---

## 3.11 Maven + the wrapper (`mvnw`)

**What is a build tool?** The program that downloads your dependencies, compiles the code,
runs tests, and packages the result (here: one runnable `.jar`). **Maven** is Java's most
common one; `pom.xml` declares the project and its dependencies.

**What is the wrapper?** `./mvnw` is a tiny script that downloads *the exact Maven version
the project wants* (3.9.16, pinned in `.mvn/wrapper/maven-wrapper.properties`) and runs it.
Anyone — and CI — can build with zero setup beyond a JDK. This repo uses the modern
"script-only" wrapper, so no binary `.jar` blob is committed to git.

---

## 3.12 Testcontainers (overview; details in chapter 6)

**What it is:** a library that starts real services in Docker *from inside your tests* —
here, a throwaway PostgreSQL 16 that lives only for the test run.

**Why:** the integration tests exercise login, permissions, pairing, signed URLs and schedule
conflicts against the *real* database engine with the *real* Flyway migrations — things an
in-memory fake database would only approximate. One version note from `pom.xml`: the
Testcontainers version is pinned to 1.21.3 because the older one bundled by Spring Boot can't
talk to Docker Engine 25+.

---

**Next:** [Chapter 4 — Frontend technologies →](04-frontend-tech.md)
