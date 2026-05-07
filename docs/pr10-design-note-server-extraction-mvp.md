# PR10 Design Note: Server Extraction MVP

## Purpose

This document defines the first server-authoritative multiplayer milestone after local separation and persistence
readiness.

By PR10:

- authoritative session handling is already local and command-driven
- session snapshots are serializable and restorable
- the Processing client is already acting mostly as presentation

PR10 should move application/domain authority into a backend host with the smallest possible transport model that still
proves the architecture.

## PR10 Goal

After PR10:

- one backend process can host one or more Monopoly sessions
- clients submit commands to backend
- backend is authoritative
- clients receive full session snapshots
- bots run on backend
- autoplay uses the same backend bot path

This is the first real server-hosted MVP.

## Explicit Non-Goals For PR10

PR10 must not:

- optimize for diff streaming yet
- solve production-grade authentication
- solve matchmaking at scale
- support every possible future client type immediately
- redesign all command/event types for internet-grade protocol evolution

PR10 is a functional MVP, not the final network architecture.

## MVP Transport Choice

Recommended transport:

- websocket session channel

Recommended payload shape:

- client -> server: command envelope
- server -> client: full `SessionSnapshot` plus small metadata envelope

Do not start with:

- event diff protocol
- mixed command/query HTTP + websocket split
- partial patch streams

Reason:

- full snapshots are already a locked product decision
- this minimizes protocol complexity
- it gets multiplayer moving faster

## Authoritative Model

Backend owns:

- session lifecycle
- command validation
- command handling
- bots/autoplay
- persistence

Client owns:

- rendering
- local input collection
- local locale
- presentation-only controls
- animations

If a decision changes game state, it belongs on backend.

## Recommended Backend Components

### `SessionHost`

Responsibilities:

- hold authoritative session instances
- dispatch commands serially per session
- persist snapshots
- expose session read model to transport layer

### `SessionCommandDispatcher`

Responsibilities:

- map incoming transport envelopes to session commands
- route by session ID
- return command result metadata

### `SessionBotRunner`

Responsibilities:

- run bot turns on backend timing loop
- submit commands to same session command path
- honor control mode / autoplay state

### `SessionConnectionRegistry`

Responsibilities:

- track clients subscribed to sessions
- map client to controllable seat IDs
- support reconnect

## Client Model

The Processing client should become a thin network client with:

- `RemoteSessionClient`
- `RemoteSessionViewStore`
- command submission adapter
- reconnect handling

Do not let the Processing client keep a second authoritative rule engine.

For local singleplayer, two acceptable transitional options exist:

1. embedded local backend in same process
2. legacy local application mode behind same client-facing interface

Recommendation:

- prefer embedded local backend if feasible
- this reduces the chance of maintaining two codepaths for too long

## Seat / Control Semantics

Keep the already locked model:

- authority is on seats, not clients
- one client may eventually control multiple seats
- MVP UX may still optimize for one primary seat
- autoplay is seat control mode, not a different player type

Server-side seat metadata should include:

- `seatId`
- `playerId`
- `seatKind`
- `controlMode`
- `controllingClientIds`

## Snapshot Sync Strategy

Recommended server push model:

after every accepted command:

1. rebuild full session snapshot
2. push snapshot to all subscribed clients

Additionally:

- push on bot actions
- push on reconnect
- push when autoplay changes seat behavior

Do not try to suppress "redundant" snapshots early.

## Reconnect Model

Minimum reconnect behavior:

- client reconnects with session ID and client identity token
- backend restores latest persisted snapshot
- backend pushes full snapshot immediately
- client resumes rendering from that snapshot

For MVP, it is acceptable if reconnect replays no animation and just snaps to current state.

## Persistence Interaction

PR10 assumes PR9 exists.

Recommended persistence policy:

- persist snapshot after each accepted command
- optionally coalesce if performance later requires it

For MVP correctness matters more than write efficiency.

## Security / Trust Model For MVP

Keep it simple but explicit:

- clients are not trusted for rule outcomes
- clients may only submit commands for seats they control
- backend validates actor identity for each command

For MVP, a simple local/private-game token model is acceptable.

Do not ship server-authoritative multiplayer with client-trusted seat control.

## Recommended Package Direction

Likely new backend packages:

- `src/main/java/fi/monopoly/server/session`
- `src/main/java/fi/monopoly/server/transport`
- `src/main/java/fi/monopoly/server/bot`
- `src/main/java/fi/monopoly/server/persistence`

Likely new client-side packages:

- `src/main/java/fi/monopoly/client/session`
- `src/main/java/fi/monopoly/client/transport`

If repository split becomes desirable later, these packages can be extracted into modules.

## Refactoring Strategy

### Step 1

Introduce a client-facing session interface that works for both local and remote mode.

Example direction:

- `SessionClientGateway`
- `submitCommand(...)`
- `currentSnapshot()`
- `subscribe(...)`

### Step 2

Implement embedded backend host using existing authoritative session services.

### Step 3

Move bot scheduling into backend host loop.

### Step 4

Switch Processing presentation to read from remote snapshot store.

### Step 5

Add reconnect and persistence integration.

## Tests Required For PR10

### Unit tests

- command routing and seat authorization
- session host serial execution guarantees
- bot runner submits commands through same path as humans

### Integration tests

- host session + connect client + play a short game
- reconnect client during property purchase
- reconnect client during debt
- reconnect client during auction
- autoplay on server-controlled seat continues correctly

### Soak / simulation tests

- multiple backend bot games without deadlock
- snapshot push after each accepted command

## Review Checklist For PR10

PR10 is acceptable only if:

- backend is authoritative for rule changes
- clients no longer mutate gameplay state locally
- bots run on backend command path
- reconnect can restore a live session from persisted snapshot
- protocol still uses full snapshots, not premature diffs

## Recommended Next Step After PR10

After PR10, the next likely wave is:

- diff/patch optimization
- improved seat/client identity model
- dedicated mobile/web clients

Those should come only after server-authoritative MVP is already working end-to-end.
