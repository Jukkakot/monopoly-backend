# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
mvn test                                          # run all tests (~1 min, must pass before committing)
mvn test -Dtest=TurnActionCommandHandlerTest      # run single test class
mvn test -Dtest=TurnActionCommandHandlerTest#methodName  # run single test method
mvn clean package -DskipTests                    # build fat JAR → target/monopoly-backend.jar
java -jar target/monopoly-backend.jar            # start server (default port 10000)
PORT=8080 java -jar target/monopoly-backend.jar  # local dev — matches client's default VITE_API_BASE
```

Java 21, Maven 3.9+. Deployed on Render: `https://monopoly-backend-bv41.onrender.com` (client: `monopoly-client` repo, GitHub Pages).

## Architecture

The server is a stateful, multi-session Monopoly game backend. Clients communicate over HTTP + SSE. All game state is in-memory; sessions expire after a configurable TTL.

**Layer stack** (top to bottom):

| Layer | Key class | Role |
|---|---|---|
| HTTP/Transport | `SessionHttpServer` (Javalin 7) | Routes HTTP requests, streams SSE, validates player tokens |
| Session registry | `SessionRegistry` | Creates/evicts sessions, starts bots |
| Command bus | `SessionCommandPublisher` | Thread-safe dispatch, SSE fanout to listeners |
| Application | `SessionApplicationService` | Routes `SessionCommand` to subsystem handlers |
| Domain | `SessionState` (immutable record) | Pure game logic, no framework coupling |

**Command flow:** HTTP POST → `SessionCommandMapper` deserializes JSON → `SessionCommandPublisher.publish()` → `SessionApplicationService.handle()` routes to the appropriate handler (turn / auction / purchase / debt / trade) → returns new `SessionState` → publisher pushes `ClientSessionSnapshot` to all SSE listeners.

**Immutability rule:** `SessionState` and all nested records are Java records. Command handlers always return a *new* state — never mutate.

## Validation philosophy

The backend must be the authoritative enforcer of game rules. A frontend bug, race
condition, or duplicate request must be caught and rejected by the backend — not
silently accepted and processed into invalid game state.

Every command handler should:
1. Validate the current turn phase is appropriate for the command
   (e.g. EndTurn rejected in WAITING_FOR_ROLL, building commands only in WAITING_FOR_END_TURN)
2. Check relevant TurnState flags (canRoll, canEndTurn) in addition to phase
3. Validate game-state preconditions the gateway does not already enforce

When in doubt, reject with a clear error code rather than processing a command that
arrives in an unexpected state. A rejected command is recoverable; bad game state is not.

## API contract (openapi.yaml)

`src/main/resources/openapi.yaml` is the authoritative API spec, served from the
classpath at `/openapi.yaml` and rendered at `/docs` (Swagger UI). **Any change to
`SessionHttpServer` routes, request fields, or response shapes must update this file in
the same commit.** The client repo (`monopoly-client`) relies on this spec when changing
the frontend↔backend interface.

Note: `docs/` is gitignored (contains local planning notes and a stale openapi copy) —
never edit `docs/openapi.yaml` expecting it to take effect.

## Bot subsystem

All bots play at one (strong) level — `BotDifficulty` was deleted; never reintroduce
difficulty flags. Tuning happens via `StrongBotConfig` weights.

| Class | Scope | Role |
|---|---|---|
| `StrongBotConfig` | main | ~25 tunable weights + named presets; `forPlayerCount()` picks the preset (≤2 → `aggressive`, 3 → `sixPlayer`, 4–6 → `defaults`); `forSeat()` adds ±10 % per-seat mutation; `humanlike()` models a non-expert human for robustness validation |
| `StrongBotStrategy` | main | Scoring functions (buyScore, buildGroupScore, dynamicReserve, …) used by both the runtime driver and the tournament engine |
| `PureDomainBotDriver` | main | Runtime bot agent — listens to snapshots, submits commands with think-delay |
| `BotTournament` | test | Headless game engine — round-robin / sampled tournaments / evolutionary search, fully parallelised |
| `BotEvolutionTest` | test | Manual tuning workflow (all `@Disabled`): ablationStudy → evolveSmallGame/evolveLargeGame → quickBenchmark. Extensive Javadoc in the file documents the full step-by-step process |
| `BotQualityRegressionTest` | test | Manual regression guard — verifies preset win-rate ordering after strategy changes |

**Tuning workflow:** run `ablationStudy` first to find high-signal parameters (~6 of 25
matter; the rest are noise at ±25 % perturbation). Then evolve, then verify with
`quickBenchmark` — which includes a humanlike bracket to catch bot-vs-bot overfitting.
Config tuning has largely converged; further bot strength requires strategy-code changes
(trade logic is property+cash only, auction logic ignores opponents' cash, debt logic
sells cheapest first). Note: `preferJailLateGame`, `jailExitThreshold`,
`bankruptcyAversion`, `jailCardHoldBias` exist in the config but are NOT wired into
strategy code — changing them has no effect.

## Package layout

```
fi.monopoly.
  server.          # BackendMain, SessionRegistry, SessionCommandPublisher, bot classes
  server.transport # SessionHttpServer, SessionCommandMapper (HTTP/SSE glue)
  application.session  # SessionApplicationService + subsystem handlers
  application.command  # ~20 command record types (RollDiceCommand, BuyPropertyCommand, …)
  application.result   # CommandResult, CommandRejection
  domain.session       # SessionState, SeatState, PlayerSnapshot, TurnState, …
  domain.turn          # TurnPhase enum (WAITING_FOR_ROLL, WAITING_FOR_DECISION, …)
  client.session       # ClientSessionSnapshot, ClientSessionListener
  infrastructure.persistence  # JSON file save/load
```

## Testing patterns

- **Unit tests** (e.g. `TurnActionCommandHandlerTest`) construct a handler with a `FakeGateway` and assert `CommandResult.accepted()` / rejection codes — no HTTP, no server.
- **Integration tests** (e.g. `SessionRegistryHttpIntegrationTest`, `HttpApiE2EGameTest`) spin up a real in-process Javalin server and hit it with HTTP.
- **Simulation tests** (`PureDomainGameSimulationTest`) run bot-vs-bot games to end and verify no crashes.
- The client repo has a Vitest rule suite (`e2e/rules/`) that runs against a live backend — when changing game rules, check whether those tests need updating too.

**Bug → test rule:** When a bug is fixed, always add a regression test that would have caught it. Name the test after the scenario, not the fix (e.g. `strongBotBidsWhenHumanBidReachesFacePrice`). Place it in the nearest existing test class.

## Logging gotcha

Changing a log level in `logback.xml` requires updating **both** the `<logger>` element
**and** the ConsoleAppender's `ThresholdFilter` — changing only one has no visible effect.

## Known backlog (TODO.txt)

- Session persistence — currently in-memory only; JSON/SQLite persistence is needed before production. Sessions are lost on every Render deploy/restart.
