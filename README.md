# Distributed Tic Tac Toe

Three microservices play Tic Tac Toe against each other while you watch the board fill in live.

- **Game Engine Service** owns the rules: board state, move validation, game outcome.
- **Game Session Service** manages sessions and generates moves for both players, sending each one
  to the engine over REST.
- **Game UI Service** serves a React board and bridges game events from Kafka to the browser over
  Server-Sent Events.

```
                    POST /games/{id}/move  (REST)
  ┌────────────────┐ ───────────────────────► ┌───────────────────┐
  │ Session Service│                          │   Engine Service  │
  │      :8082     │ ◄─────────────────────── │       :8081       │
  └────────────────┘   board + status         └───────────────────┘
          │                                             │
          │ SessionCreated                              │ MoveApplied
          │ SimulationStarted                           │ GameFinished
          │ SimulationFailed                            │
          ▼                                             ▼
        ┌─────────────────────────────────────────────────┐
        │            Kafka topic: game-events             │
        │            (keyed by game id)                   │
        └─────────────────────────────────────────────────┘
                              │
                              │ @KafkaListener
                              ▼
  ┌──────────┐   SSE    ┌───────────────────┐
  │ Browser  │ ◄─────── │    UI Service     │  also proxies /api/sessions
  │  React   │          │       :8080       │  to the Session Service
  └──────────┘          └───────────────────┘
```

---

## Quick start

```bash
docker compose up --build
```

Then open **http://localhost:8080**, pick a strategy, and press **Start simulation**. The board
fills one cell at a time as the two automated players trade moves.

First build takes a few minutes: it compiles all modules and downloads a Node toolchain to build
the React app. Nothing needs to be installed on your machine except Docker.

| Service | URL | Notes |
|---|---|---|
| UI | http://localhost:8080 | The board |
| Game Engine | http://localhost:8081/swagger-ui.html | OpenAPI |
| Game Session | http://localhost:8082/swagger-ui.html | OpenAPI |
| Kafka | `localhost:9092` | KRaft, single node |

Shut down with `docker compose down`.

### Running without Docker

Requires JDK 21 and Maven. Kafka is optional — without it, REST still works and the board updates
by polling instead of streaming.

```bash
mvn clean package                      # builds everything incl. the React bundle

java -jar game-engine-service/target/game-engine-service-1.0.0.jar
java -jar game-session-service/target/game-session-service-1.0.0.jar
java -jar game-ui-service/target/game-ui-service-1.0.0.jar
```

For frontend work with hot reload, run the backend as above and then:

```bash
cd game-ui-service/src/main/frontend && npm run dev     # http://localhost:5173
```

To skip the Node toolchain entirely when iterating on backend code: `mvn package -Pskip-frontend`.

---

## API

### Game Engine Service (`:8081`)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/games` | Create a game. Idempotent when you supply a `gameId`. |
| `POST` | `/games/{gameId}/move` | Validate and apply a move; returns the resulting state. |
| `GET` | `/games/{gameId}` | Current board and status. |

### Game Session Service (`:8082`)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/sessions` | Create a session and its backing game. Body: `{"strategy":"random"\|"blocking"}` |
| `POST` | `/sessions/{sessionId}/simulate` | Start the automated game. Returns **202**. |
| `GET` | `/sessions/{sessionId}` | Session status, board and full move history. |

### Game UI Service (`:8080`)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/sessions` | Proxied to the session service. |
| `POST` | `/api/sessions/{sessionId}/simulate` | Proxied. |
| `GET` | `/api/sessions/{sessionId}` | Proxied. |
| `GET` | `/api/sessions/{sessionId}/stream` | SSE: `snapshot`, then `move-applied`, `game-finished`, … |

Errors are RFC 7807 `application/problem+json` with a stable `code`:

```json
{
  "type": "urn:tictactoe:error:cell-occupied",
  "title": "Cell is already occupied",
  "status": 409,
  "detail": "Cell 4 is already occupied by X",
  "code": "cell-occupied"
}
```

| `code` | Status | Meaning |
|---|---|---|
| `position-out-of-range` | 400 | Cell index outside 0–8 |
| `cell-occupied` | 409 | Something is already there |
| `out-of-turn` | 409 | The other player is to move |
| `game-already-finished` | 409 | Game is over |
| `concurrent-modification` | 409 | Lost an optimistic-lock race; re-read and retry |
| `game-not-found` / `session-not-found` | 404 | Unknown id |
| `unknown-strategy` | 400 | No such move strategy |
| `engine-unavailable` | 503 | Engine unreachable after retries |

---

## How a game runs

1. `POST /api/sessions` → the session service asks the engine to create a game, reusing its own
   `sessionId` as the `gameId` so one identifier correlates everything.
2. The browser opens the SSE stream and receives a `snapshot` of current state.
3. `POST /api/sessions/{id}/simulate` returns **202** and the work moves to a background thread.
4. That thread loops: read the state the engine last returned → ask the strategy for a cell → `POST`
   the move → record what the engine says. It never reconstructs the board locally, so the session
   cannot drift from the authority.
5. The engine publishes `MoveApplied` after each accepted move and `GameFinished` on a terminal
   state. The UI service consumes them and writes them to the open SSE connections.

---

## Design decisions and trade-offs

**Kafka is not strictly necessary here, and that is deliberate.** At this scale the session service
could hold the SSE connections itself and push updates directly. Kafka is here because it buys three
properties that matter once the system is real: the engine does not need to know the UI exists;
new consumers (analytics, an audit log, a search indexer) can be added without touching the
producer; and the event log is replayable. That is a genuine architectural argument, not a
technology tally — and if the answer were "we only ever need one consumer", the right call would be
to drop the broker.

**REST between session and engine, events for everything else.** Commands that need an answer —
"apply this move, is it legal?" — are synchronous request/response. Facts that others may care about
— "this move happened" — are events. Mixing the two directions gives each its natural shape.

**The engine is the only writer of game state.** The session service holds the last state the engine
reported, never a locally computed board. If they can disagree, they eventually will.

**Simulation is asynchronous and paced.** `POST /simulate` returns 202 rather than holding the
connection for the length of a game. The 600 ms pause between moves is presentation pacing so the
board animates rather than snapping to a finished game; it is not a concurrency control.

**Concurrent moves resolve by optimistic locking.** `@Version` on the game entity means the loser of
a race gets a 409 and can re-read, rather than silently overwriting the winner. There is a test that
fires ten simultaneous moves at the same cell and asserts exactly one succeeds.

**Retries distinguish "try again" from "you are wrong".** Resilience4j retries transport failures and
5xx only. A 4xx is the engine saying the move breaks the rules; replaying it produces the same
answer and hides the bug that generated it.

**Events are published off the request thread.** `KafkaTemplate.send` looks asynchronous but blocks
while the producer fetches topic metadata — and with observation enabled it also resolves the cluster
id through an admin call. Called inline, an unreachable broker stalls the HTTP thread of a move that
has already been committed, turning a Kafka outage into a service outage. Each producer hands sends
to a single background thread: one thread preserves per-game ordering, and a bounded queue drops
events with a warning rather than exhausting memory.

**The engine publishes after commit, not during.** A `@TransactionalEventListener(AFTER_COMMIT)`
means a move that gets rolled back is never announced. The remaining gap is at-most-once delivery: if
the process dies with events queued, they are lost. See "what I would change" below.

**Every UI replica gets every event.** The consumer group id is unique per instance
(`ui-service-${random.uuid}`). This is broadcast, not work sharing: a browser attached to replica 2
needs moves regardless of which replica consumed them, and a shared group would partition events
across replicas and silently drop updates. The consequence is that offsets are meaningless across
restarts, so the consumer starts at the latest offset instead of replaying history.

**SSE, not WebSockets.** The browser only receives; it never pushes moves. SSE is the smaller tool
that fits, reconnects on its own, and is plain HTTP. WebSockets would add a protocol upgrade and a
bidirectional channel for traffic that only flows one way.

**Snapshot before deltas.** SSE has no history, so a browser connecting mid-game would render an
empty board and only catch up from the next move. The stream therefore opens with the current state,
then streams changes.

**No service registry, and that is the point.** Service discovery already happens: in Compose,
`http://game-engine-service:8081` resolves through Docker's embedded DNS, and the identical URL
resolves through a Service in Kubernetes. Eureka solves *client-side* discovery — many instances at
unpredictable addresses, each client keeping a local registry to balance across them — which is a
problem this system does not have, with three services at stable names injected as environment
variables. Adding it would mean a fourth process to run, a registry that becomes a single point of
failure, and registration lag on startup, in exchange for replacing DNS that already works. Worth
noting too that Spring Cloud Netflix is largely retired: Ribbon, Hystrix and Zuul are gone and
Eureka is the last piece standing, which is why resilience here uses Resilience4j, Hystrix's
successor. The gateway half of the same idea *is* present — see the BFF below. If instance counts
became dynamic, the answer would be the platform's own discovery or a service mesh, not a registry
maintained in application code.

**The UI service is a BFF.** The browser talks only to `:8080`, which proxies to the session
service. Same-origin, so no CORS configuration to get wrong, and the internal services need not be
reachable from outside the cluster. The proxy strips hop-by-hop headers and rewrites `Location` —
forwarding the upstream's `Transfer-Encoding` makes the container emit it twice and the browser
discard the body.

**State is in memory, as the assignment allows.** The engine uses H2 with JPA (so the persistence
model is real even though the store is not), the session service a `ConcurrentHashMap`. Everything is
lost on restart. Both sit behind narrow interfaces so swapping in Postgres or Redis is a
file-sized change, not a refactor.

### What I would change for production

- **Transactional outbox** in the engine: write events to a table in the same transaction as the
  move and relay them separately. That closes the at-most-once gap.
- **Idempotent consumers** keyed on game id and move number, since an outbox relay delivers at least
  once.
- **Real persistence** — Postgres for games, Redis for sessions — plus schema migrations.
- **A dedicated gateway** once there is more than one browser-facing concern — auth, rate limiting,
  routing to several backends — rather than folding them into the BFF. Discovery would come from the
  platform (Kubernetes Services, or a mesh), not a registry in application code.
- **Authentication**: OAuth2 resource servers on the services, with the UI service performing the
  token exchange.
- **A shared SSE fan-out** (or sticky routing) if the UI service ever needs to scale past what
  per-instance broadcast comfortably handles.

---

## Testing

```bash
mvn verify              # unit, slice and integration tests — no Docker required
mvn verify -Pe2e        # adds the full-stack end-to-end test — needs Docker
```

| Scope | What it covers |
|---|---|
| `BoardTest` | All eight winning lines, draws, illegal positions, immutability |
| `GameTest` | Turn order, occupied cells, moves after game over, validation ordering |
| `GameControllerTest` | Status codes and problem bodies for every error case |
| `GameEngineIntegrationTest` | Full game over HTTP against embedded Kafka; event ordering; ten-way concurrent move race |
| `BlockingMoveStrategyTest` | Win, block, centre, corner; 500 random boards never yield an illegal cell |
| `SessionSimulationIntegrationTest` | WireMock engine: whole game, alternating players, 5xx retried, 4xx not retried, engine outage fails the session and publishes the event |
| `GameUiIntegrationTest` | Kafka event → real SSE client; per-game isolation; emitter cleanup; upstream errors relayed |
| `FullGameFlowIT` (`-Pe2e`) | Real Kafka container, all three services as separate processes, driven from the UI as a browser would |

The end-to-end test asserts what a reviewer would check by hand: the game reaches a legal terminal
state in 5–9 moves, players alternate starting with X, the number of marks matches the move count,
the session and the engine agree on the final board, and every move arrived over SSE in order.

---

## Configuration

All settings have working defaults; these are the ones worth knowing.

| Variable | Service | Default | Purpose |
|---|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | all | `localhost:9092` | Broker address |
| `GAME_ENGINE_URL` | session | `http://localhost:8081` | Engine base URL |
| `GAME_SESSION_URL` | ui | `http://localhost:8082` | Session service base URL |
| `MOVE_DELAY` | session | `600ms` | Pause between generated moves |
| `MOVE_STRATEGY` | session | `random` | Default strategy: `random` or `blocking` |
| `SERVER_PORT` | all | 8080/8081/8082 | HTTP port |

---

## Project layout

```
game-contracts/          DTOs, Kafka event types, and the shared Board value object
game-engine-service/     :8081  rules, H2 + JPA, event publishing
game-session-service/    :8082  sessions, move strategies, engine client, simulation loop
game-ui-service/         :8080  BFF proxy, SSE bridge, React SPA (src/main/frontend)
integration-tests/       full-stack end-to-end test (-Pe2e)
```

Built with Java 21, Spring Boot 3.5, Spring Kafka, Resilience4j, React 18 + TypeScript + Vite.
