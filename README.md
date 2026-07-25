# Planning Poker

Real-time, multi-participant story estimation ("planning poker") for agile teams.
Spring Boot 3.x / Java 21 backend with a REST API, STOMP-over-WebSocket real-time
layer (SockJS fallback), and a dependency-free single-page frontend.

## Features

- **Sessions**: create a session with a name, deck, and settings (auto-reveal,
  allow observers, allow vote change); share via an 8-character non-guessable
  code or join link. Idle sessions auto-close after a configurable timeout
  (default 4 h). Only the facilitator can close a session.
- **Participants**: join as a guest with just a display name, or as a registered
  user. Roles: `FACILITATOR`, `VOTER`, `OBSERVER`. The facilitator can kick
  participants, change roles, and transfer facilitation; if the facilitator
  leaves, the oldest active non-observer is promoted automatically.
  Join/leave/connection changes broadcast in real time.
- **Stories**: add, edit, reorder, delete; paste-import one title per line;
  statuses `PENDING` → `VOTING` → `ESTIMATED`; one story voting at a time.
- **Voting & reveal**: votes stay hidden until reveal — pre-reveal broadcasts
  carry only "has voted" flags, never values. Voters can change their vote
  before reveal (if enabled). Reveal by facilitator or automatically when all
  connected voters voted (auto-reveal). On reveal everyone simultaneously
  receives all votes plus statistics: distribution, count, min/max, and (for
  numeric decks) average and median, with a consensus indicator. The
  facilitator can reset for a re-vote or record any final estimate.
- **Decks**: built-in Fibonacci, Modified Fibonacci, T-shirt, Powers of 2, and
  Linear (all include `?` and `☕`); ad-hoc custom decks per session, and saved
  custom decks for registered users. Statistics ignore non-numeric cards.
- **Export**: session results as CSV or JSON (facilitator only).
- **Security**: JWT for both REST and the STOMP handshake; guests get signed
  session-scoped participant tokens; per-action server-side authorization;
  rate-limited session creation and join; Bean Validation on all inputs.
- **Privacy**: story text and participant names are end-to-end encrypted in the
  browser with a room key the server never receives, so neither a database
  admin nor the operator can read them. Session names, vote values, final
  estimates and user emails are encrypted at rest with a server-held key.
  Client IPs are never retained and session codes stay out of logs.
  **Read [docs/PRIVACY.md](docs/PRIVACY.md)** for the threat model, the
  deliberate limits (votes are readable by the operator; joining needs the
  invite link or room key; export is rendered client-side) and the residual
  exposure.

## Running

Requirements: Java 21.

```bash
./gradlew bootRun
```

Open http://localhost:8080 — the dev profile uses an in-memory H2 database and
the in-process STOMP broker, so no external services are needed.

### Production (PostgreSQL)

```bash
export APP_JWT_SECRET="$(head -c 48 /dev/urandom | base64)"
# base64 of exactly 32 bytes; rotating it makes existing at-rest columns
# unreadable, so re-encrypt before rotating
export APP_DATA_KEY="$(head -c 32 /dev/urandom | base64)"
export DB_URL="jdbc:postgresql://db:5432/planningpoker"
export DB_USERNAME=planningpoker
export DB_PASSWORD=...
./gradlew bootJar
java -jar build/libs/planning-poker-0.1.0.jar --spring.profiles.active=postgres
```

Serve over HTTPS in any real deployment: the browser's WebCrypto API is only
available in a secure context, and the app refuses to encrypt without it.

The schema is managed by Flyway migrations (`src/main/resources/db/migration`);
no runtime auto-DDL. For horizontal scaling, switch the simple broker in
`WebSocketConfig` to a STOMP broker relay (RabbitMQ) or Redis pub/sub so events
fan out across instances, and back the rate limiter with a shared store.
Terminate TLS (HTTPS/WSS) at the load balancer or servlet container.

## API overview

| Method | Path | Purpose | Auth |
|---|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | Registered-user tokens | — |
| POST | `/api/sessions` | Create session (creator becomes facilitator) | optional |
| GET | `/api/sessions/{code}` | Session snapshot | member |
| POST | `/api/sessions/{code}/close` | Close session | facilitator |
| POST | `/api/sessions/{code}/participants` | Join as guest/user | — |
| DELETE | `/api/sessions/{code}/participants/{id}` | Leave / kick | self / facilitator |
| PUT | `/api/sessions/{code}/participants/{id}/role` | Change role / transfer facilitation | facilitator |
| POST | `/api/sessions/{code}/stories` | Add story | facilitator |
| POST | `/api/sessions/{code}/stories/import` | Paste-import stories | facilitator |
| PUT / DELETE | `/api/sessions/{code}/stories/{id}` | Edit, reorder, delete | facilitator |
| POST | `/api/sessions/{code}/stories/{id}/select` | Start voting on story | facilitator |
| POST | `/api/sessions/{code}/stories/{id}/estimate` | Record final estimate | facilitator |
| GET | `/api/sessions/{code}/export` | Export bundle (ciphertext; the browser decrypts and writes the CSV/JSON) | facilitator |
| GET / POST | `/api/decks` | List / create decks | — / user |

Errors use a consistent body: `timestamp`, `status`, `code`, `message`, `traceId`.

### Real-time (STOMP over `/ws`, SockJS fallback)

Client → server: `/app/sessions/{code}/vote`, `/reveal`, `/reset`,
`/story/select`.
Server → client: `/topic/sessions/{code}` (typed events: participant changes,
vote flags, reveals, resets, story changes), `/user/queue/snapshot` (full state
on subscribe, for reconnection resync), `/user/queue/errors`.
The STOMP `CONNECT` requires the same Bearer token as the REST API; topic
subscriptions are checked against session membership.

## Development

```bash
./gradlew test        # unit + integration tests (H2)
./gradlew bootRun     # run locally on :8080
```

Test coverage includes voting/reveal/statistics rules, role enforcement,
pre-reveal vote hiding, multi-client STOMP fan-out, and the concurrent
auto-reveal race.

Health/metrics: `/actuator/health`, `/actuator/metrics` (Spring Boot Actuator).
