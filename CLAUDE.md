# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
mvn test                                          # run all tests
mvn test -Dtest=TurnActionCommandHandlerTest      # run single test class
mvn test -Dtest=TurnActionCommandHandlerTest#methodName  # run single test method
mvn clean package -DskipTests                    # build fat JAR → target/monopoly-backend.jar
java -jar target/monopoly-backend.jar            # start server (default port 10000)
PORT=8080 java -jar target/monopoly-backend.jar  # override port
```

Java 21, Maven 3.9+.

## Architecture

The server is a stateful, multi-session Monopoly game backend. Clients communicate over HTTP + SSE. All game state is in-memory; sessions expire after a configurable TTL.

**Layer stack** (top to bottom):

| Layer | Key class | Role |
|---|---|---|
| HTTP/Transport | `SessionHttpServer` (Javalin 7) | Routes HTTP requests, streams SSE |
| Session registry | `SessionRegistry` | Creates/evicts sessions, starts bots |
| Command bus | `SessionCommandPublisher` | Thread-safe dispatch, SSE fanout to listeners |
| Application | `SessionApplicationService` | Routes `SessionCommand` to subsystem handlers |
| Domain | `SessionState` (immutable record) | Pure game logic, no framework coupling |

**Command flow:** HTTP POST → `SessionCommandMapper` deserializes JSON → `SessionCommandPublisher.publish()` → `SessionApplicationService.handle()` routes to the appropriate handler (turn / auction / purchase / debt / trade) → returns new `SessionState` → publisher pushes `ClientSessionSnapshot` to all SSE listeners.

**Immutability rule:** `SessionState` and all nested records are Java records. Command handlers always return a *new* state — never mutate.

**Bot players** implement `ClientSessionListener`, receive the same snapshots as HTTP clients, and submit commands through the same publisher. `BotTurnScheduler` adds an artificial delay.

## Package layout

```
fi.monopoly.
  server.          # BackendMain, SessionRegistry, SessionCommandPublisher, BotDriver
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

## Known backlog (TODO.txt)

- Session persistence — currently in-memory only; JSON/SQLite persistence is needed before production.
- Player authorization — no per-seat tokens; any caller can submit commands as any playerId.
- Human-only E2E test — a regression test that doesn't rely on bots for the full game flow.
