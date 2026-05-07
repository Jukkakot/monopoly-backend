# PR9 Design Note: Persistence-Ready Authoritative Session

## Purpose

This document defines the first post-local-separation milestone.

By this point:

- authoritative session-oriented command handling already exists locally
- the major gameplay subsystems no longer depend on popup ownership
- `Game` has been reduced toward a presentation shell

PR9 should make the authoritative session model durable enough for:

- save/load
- reconnect-safe server snapshots later
- mid-session recovery without rebuilding state from legacy runtime

This is the bridge between local separation and actual backend extraction.

## PR9 Goal

After PR9:

- authoritative `SessionState` is sufficiently complete to serialize
- save/load does not depend on Processing UI objects
- pending decisions, debt, auction and trade survive snapshot/restore
- client-side projections can be rebuilt from restored state

PR9 is not yet server networking.

## Explicit Non-Goals For PR9

PR9 must not:

- add websocket or HTTP networking
- redesign the UI
- rewrite all domain model types into immutable value objects
- solve multi-session hosting

If a change is about transport or matchmaking, it belongs to PR10 or later.

## Current Problem

Even after subsystem migration, the runtime still depends on live in-memory object graphs:

- `Player`, `Property`, `Spot`, and controller state are still restored implicitly by bootstrap
- `SessionState` is authoritative for flow, but not yet a complete persistence boundary
- legacy projectors and adapters can still rebuild some parts from runtime objects rather than persisted session data

This is enough for local play, but not enough for real save/load or reconnect.

## First Technical Gap To Close

Before PR9 can be complete, the authoritative session model must explicitly carry property-level state.

At minimum this means:

- ownership
- mortgage state
- building count / hotel state
- any restore-critical property metadata that currently still lives only in runtime objects

Without this, snapshot roundtrip can preserve turn/debt/auction/trade flow, but not full board economy faithfully.

This should be treated as the first implementation sub-slice inside PR9, not as a later optional improvement.

## Required End State

PR9 should establish two distinct models:

1. `AuthoritativeSessionSnapshot`
- stable serialized shape
- no Processing references
- no ControlP5 references
- no runtime singletons

2. `RuntimeProjectionState`
- rebuilt after loading snapshot
- may contain client-local and rendering-only details

The first must be sufficient to resume gameplay.
The second is disposable.

## Snapshot Boundary

The snapshot must contain at least:

- session identity and version
- board rules/config relevant to the session
- player/seat ownership and control mode
- seat controller profile and token color
- money, jail state, bankruptcy state
- jail rounds remaining
- property ownership, mortgage state, building counts
- current turn / active actor
- pending decision payload
- debt state
- auction state
- trade state
- dice/turn-phase data needed to continue safely
- winner/game-over state

The snapshot should not contain:

- popup UI layout state
- recent message strings
- animation progress
- hovered spot
- selected deed page in sidebar
- debug overlay measurements
- client-local locale

These must remain projection/client concerns.

## Recommended Types

### New domain/infrastructure-facing types

- `fi.monopoly.persistence.session.SessionSnapshot`
- `fi.monopoly.persistence.session.PlayerSnapshot`
- `fi.monopoly.persistence.session.PropertySnapshot`
- `fi.monopoly.persistence.session.PendingDecisionSnapshot`
- `fi.monopoly.persistence.session.DebtSnapshot`
- `fi.monopoly.persistence.session.AuctionSnapshot`
- `fi.monopoly.persistence.session.TradeSnapshot`

### New mapping layer

- `fi.monopoly.application.session.persistence.SessionSnapshotMapper`

Responsibilities:

- map authoritative runtime session to snapshot
- restore authoritative session from snapshot
- validate snapshot version compatibility

### New application service

- `fi.monopoly.application.session.persistence.SessionPersistenceService`

Responsibilities:

- save current session
- load session
- return restored authoritative session root

## Recommended Serialization Strategy

Use a simple, explicit serialized form first:

- JSON snapshot
- explicit version field
- explicit IDs for players, seats, properties and decisions

Do not start with Java native serialization.

Reason:

- brittle
- hard to evolve
- poor control over compatibility

## Versioning Rules

Every snapshot should contain:

- `snapshotSchemaVersion`
- `gameRulesVersion` or equivalent compatibility marker

Minimum versioning policy:

- loading same-version snapshots must work
- older snapshots may be rejected explicitly
- do not silently coerce unknown versions

## Restore Strategy

Recommended restore pipeline:

1. deserialize `SessionSnapshot`
2. validate schema version
3. reconstruct authoritative `SessionState`
4. reconstruct legacy runtime-backed objects needed by current client
5. rebuild presentation projections from authoritative state

Important:

authoritative state must come first.
Legacy runtime objects should become derived from restored session, not the other way around.

## Interaction With Existing Runtime

PR9 does not need to delete `MonopolyRuntime`, but it must narrow its role during restore.

Target direction:

- runtime provides client infrastructure and services
- persistence restores authoritative session first
- runtime-backed `GameSession` is then reattached to restored state

This is effectively a reverse dependency compared with the older model.

## Save/Load Scope Recommendation

For the first implementation, support these restore points:

- between turns
- during property purchase pending decision
- during debt remediation
- during auction
- during trade negotiation
- after game over

Do not initially support:

- restoring mid-animation frame-exactly
- restoring transient popup-local button focus

On load, animations may restart from a clean non-animated state.

## Suggested File Moves / New Types

Likely new files:

- `src/main/java/fi/monopoly/persistence/session/SessionSnapshot.java`
- `src/main/java/fi/monopoly/persistence/session/SessionSnapshotMapper.java`
- `src/main/java/fi/monopoly/persistence/session/JsonSessionStore.java`
- `src/main/java/fi/monopoly/application/session/persistence/SessionPersistenceService.java`

Likely modified files:

- session/domain state types to ensure complete snapshot coverage
- `GameSession` / runtime reattachment code
- save/load entry points in presentation layer

## Refactoring Strategy

### Step 1

Define snapshot schema and mapper tests first.

Why:

- forces completeness
- catches missing subsystem fields early

### Step 2

Add authoritative property-state coverage to the session model.

Why:

- save/load fidelity depends on this
- server extraction later depends on this even more

### Step 3

Implement one-way export from authoritative session to snapshot.

### Step 4

Implement restore into authoritative session state.

### Step 5

Reconnect local presentation runtime to restored state.

### Step 6

Add save/load UI entry points if desired.

## Tests Required For PR9

### Unit tests

- snapshot mapping for players/properties
- snapshot mapping for pending decision / debt / auction / trade
- schema version validation

### Integration tests

- save and load mid-property-purchase
- save and load mid-debt-remediation
- save and load mid-auction
- save and load mid-trade
- save and load game-over state

### Regression tests

- loaded session continues command processing normally
- bots can continue acting after restore

## Review Checklist For PR9

PR9 is acceptable only if:

- authoritative state can be saved without UI object references
- restored session can continue gameplay from key subsystem states
- save/load does not depend on popup text history or animation state
- persistence logic does not reintroduce `Game` or `PopupService` as gameplay authority

## Recommended Next Step After PR9

After PR9, the next milestone is server extraction:

- backend session host
- command transport
- snapshot sync to clients

At that point the project has a real persistence boundary and can move across process boundaries without inventing it
later.
