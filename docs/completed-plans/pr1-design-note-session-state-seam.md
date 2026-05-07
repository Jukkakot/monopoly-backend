# PR1 Design Note: Session State Seam

## Purpose

This document defines the exact scope of the first implementation PR for the local separation effort.

PR1 is not meant to migrate gameplay behavior yet. Its purpose is to create the first real seam:

- authoritative session snapshot model
- command/result/event primitives
- minimal application service shell
- first projection from current live runtime objects into the new model

If PR1 grows beyond that, it will become too risky and too hard to review.

## PR1 Goal

After PR1:

- the codebase contains a real `SessionState` model separate from current UI/runtime objects
- the codebase contains a minimal command boundary abstraction
- `Game` can ask an application service for a snapshot/projection of current authoritative-looking state
- no major gameplay flow has been migrated yet
- current gameplay should behave the same as before

This is a seam-creation PR, not a behavior-migration PR.

## Explicit Non-Goals For PR1

PR1 must not:

- migrate property purchase logic to command handling
- migrate rent/debt behavior
- migrate auctions
- migrate trades
- rewrite bot logic
- replace `GameSession`
- remove `MonopolyRuntime`
- redesign popup rendering
- change save/load or networking behavior

If a change belongs to one of those, it is out of scope for PR1.

## Package Plan

Recommended package layout for PR1:

- `fi.monopoly.application.session`
- `fi.monopoly.application.command`
- `fi.monopoly.application.result`
- `fi.monopoly.domain.session`
- `fi.monopoly.domain.turn`
- `fi.monopoly.domain.decision`
- `fi.monopoly.presentation.session`

Reason:

- keeps new types out of existing `components` clutter
- starts creating the future module boundaries without needing multi-module immediately
- makes review easier because new seam code is visibly separate from legacy classes

## Exact New Types For PR1

### Domain-side state types

#### `fi.monopoly.domain.session.SessionState`

PR1 minimal fields:

- `String sessionId`
- `long version`
- `SessionStatus status`
- `List<SeatState> seats`
- `List<PlayerSnapshot> players`
- `TurnState turn`
- `PendingDecision pendingDecision`
- `String winnerPlayerId`

Notes:

- keep it intentionally small
- do not add debt/trade/auction state yet in PR1
- those will come in later slices

#### `fi.monopoly.domain.session.SessionStatus`

PR1 values:

- `IN_PROGRESS`
- `PAUSED`
- `GAME_OVER`

Do not introduce `LOBBY` yet unless PR1 genuinely needs it.

#### `fi.monopoly.domain.session.SeatState`

PR1 minimal fields:

- `String seatId`
- `int seatIndex`
- `String playerId`
- `SeatKind seatKind`
- `ControlMode controlMode`
- `String displayName`

Notes:

- `seatId` can be synthetic for now, for example `"seat-0"`
- `displayName` is included because current UI already depends heavily on it

#### `fi.monopoly.domain.session.SeatKind`

PR1 values:

- `HUMAN`
- `BOT`

#### `fi.monopoly.domain.session.ControlMode`

PR1 values:

- `MANUAL`
- `AUTOPLAY`

Notes:

- even though autoplay behavior is not migrated in PR1, the type should already exist
- this avoids redesign later

#### `fi.monopoly.domain.session.PlayerSnapshot`

Use `PlayerSnapshot` name in PR1 to avoid confusion with
current [Player.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Player.java).

PR1 minimal fields:

- `String playerId`
- `String seatId`
- `String name`
- `int cash`
- `int boardIndex`
- `boolean bankrupt`
- `boolean eliminated`
- `boolean inJail`
- `int getOutOfJailCards`
- `List<String> ownedPropertyIds`

Notes:

- keep this lightweight
- use snapshot naming intentionally, because this is still a projection from legacy runtime objects

#### `fi.monopoly.domain.turn.TurnState`

PR1 minimal fields:

- `String activePlayerId`
- `TurnPhase phase`
- `boolean canRoll`
- `boolean canEndTurn`

Notes:

- do not overmodel dice and doubles yet
- only include the fields already needed to express current action availability

#### `fi.monopoly.domain.turn.TurnPhase`

PR1 values:

- `WAITING_FOR_ROLL`
- `WAITING_FOR_DECISION`
- `RESOLVING_DEBT`
- `WAITING_FOR_END_TURN`
- `GAME_OVER`
- `UNKNOWN`

Notes:

- `UNKNOWN` is deliberate
- PR1 will still project from legacy flow, and not every current substate will map cleanly yet

#### `fi.monopoly.domain.decision.PendingDecision`

PR1 minimal fields:

- `String decisionId`
- `DecisionType decisionType`
- `String actorPlayerId`
- `List<DecisionAction> allowedActions`
- `String summaryText`

Notes:

- `summaryText` is acceptable in PR1 as a temporary bridge
- long term, payload should become typed
- do not block PR1 on fully typed decision payloads

#### `fi.monopoly.domain.decision.DecisionType`

PR1 values:

- `PROPERTY_PURCHASE`
- `JAIL_CHOICE`
- `GENERIC_INFO`
- `GENERIC_CONFIRM`
- `UNKNOWN`

#### `fi.monopoly.domain.decision.DecisionAction`

PR1 values:

- `PRIMARY`
- `SECONDARY`
- `BUY_PROPERTY`
- `DECLINE_PROPERTY`
- `PAY_JAIL_FINE`
- `USE_JAIL_CARD`
- `STAY_IN_JAIL`

Notes:

- `PRIMARY` / `SECONDARY` are a temporary bridge for legacy popup projection
- keep them explicitly marked as transitional in code comments

### Application-side command/result types

#### `fi.monopoly.application.command.SessionCommand`

PR1 should use a marker interface or sealed interface.

Reason:

- establishes the future command boundary with near-zero migration cost

#### `fi.monopoly.application.command.RefreshSessionViewCommand`

This is intentionally a bridge command, not a gameplay command.

Purpose:

- allows the first application service to be exercised without yet mutating gameplay through commands

Fields:

- `String sessionId`

Notes:

- yes, this is slightly artificial
- that is acceptable in PR1 because the real value is introducing the result/event/view-hint contract

#### `fi.monopoly.application.result.CommandResult`

PR1 minimal fields:

- `boolean accepted`
- `SessionState sessionState`
- `List<DomainEvent> events`
- `List<CommandRejection> rejections`
- `List<ViewHint> viewHints`

#### `fi.monopoly.application.result.CommandRejection`

PR1 minimal fields:

- `String code`
- `String message`

#### `fi.monopoly.application.result.DomainEvent`

PR1 minimal fields:

- `String eventType`
- `String actorPlayerId`
- `String summary`

Notes:

- this is intentionally light in PR1
- avoid inventing full typed event hierarchies before any gameplay slice uses them

#### `fi.monopoly.application.result.ViewHint`

PR1 minimal fields:

- `String hintType`
- `String targetId`

PR1 can survive with a tiny generic representation.

### Application service

#### `fi.monopoly.application.session.SessionApplicationService`

PR1 methods:

- `SessionState currentState()`
- `CommandResult refresh()`

Optional alternative:

- `CommandResult handle(SessionCommand command)`

Recommendation for PR1:

- use `handle(SessionCommand command)`
- even if the first real command is only `RefreshSessionViewCommand`

Reason:

- avoids redesigning the service API one PR later

### Presentation projection helper

#### `fi.monopoly.presentation.session.LegacySessionProjector`

Purpose:

- build `SessionState` from current live legacy runtime/game objects

Primary input:

- [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)
- [Players.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Players.java)
- [Board.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/board/Board.java)
- [DebtController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/DebtController.java)
- [PopupService.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PopupService.java)

Output:

- `SessionState`

Notes:

- name it `LegacySessionProjector`, not `SessionMapper`, because it is explicitly a migration bridge
- it should depend on legacy runtime objects, but the new domain/application types must not depend back on legacy
  classes

## Exact PR1 Field Source Mapping

This section reduces ambiguity when implementing the projector.

### `SessionState.sessionId`

PR1 source:

- synthetic constant like `"local-session"`

Reason:

- there is no real session id yet worth plumbing everywhere

### `SessionState.version`

PR1 source:

- start with `0`

Notes:

- do not fake a changing version in PR1 unless there is already an authoritative increment source
- versioning becomes meaningful in the next slice

### `SessionState.status`

PR1 source:

- if game over: `GAME_OVER`
- else if paused: `PAUSED`
- else `IN_PROGRESS`

Derived from [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java).

### `SeatState`

PR1 source:

- derived from current `Players` ordering and `Player` profile

Mapping:

- `seatIndex` from current player iteration order / turn order list position
- `seatKind`:
    - `HUMAN` if profile is human
    - `BOT` otherwise
- `controlMode`:
    - initially always `MANUAL` in PR1

### `PlayerSnapshot`

PR1 source:

- current [Player.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Player.java)

Mapping:

- name, money, board position, jail, GOOJF cards, ownership

### `TurnState`

PR1 source:

- current [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)
-
current [DebtController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/DebtController.java)
-
current [PopupService.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PopupService.java)

Proposed mapping:

- if game over -> `GAME_OVER`
- else if debt active -> `RESOLVING_DEBT`
- else if popup visible -> `WAITING_FOR_DECISION`
- else if roll is currently allowed -> `WAITING_FOR_ROLL`
- else if end turn is currently allowed -> `WAITING_FOR_END_TURN`
- else `UNKNOWN`

This is intentionally coarse and acceptable for PR1.

### `PendingDecision`

PR1 source:

-
current [PopupService.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PopupService.java)

Mapping strategy:

- if no popup visible -> `null`
- else infer:
    - `actorPlayerId` from current turn player when possible
    - `summaryText` from active popup message
    - `decisionType` from popup kind best-effort
    - `allowedActions` from popup actions best-effort

This is intentionally a projection bridge, not a final authoritative decision system.

## Minimal `Game` Changes Allowed In PR1

Allowed:

- construct or own `SessionApplicationService`
- call the service to obtain a `SessionState`
- optionally log or expose that state for debug/testing
- very small refactors needed to pass legacy dependencies into the projector/service

Not allowed:

- migrating popup flow into commands
- changing turn progression logic
- changing debt handling
- changing trade flow
- rewriting bot stepping
- replacing current action gating with new state logic

Rule:

- if a `Game` change alters gameplay behavior, it is almost certainly out of scope for PR1

## Minimal `PopupService` Changes Allowed In PR1

Allowed:

- add small read-only helpers if needed for projector access
- for example:
    - active popup summary
    - active popup kind
    - active popup action labels

Not allowed:

- changing popup decision semantics
- rerouting popup actions through new command handling yet

## Minimal `GameSession` Changes Allowed In PR1

Allowed:

- none unless absolutely necessary

Recommendation:

- do not
  extend [GameSession.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/GameSession.java)
  for PR1
- let it remain current runtime glue

Reason:

- PR1 is about creating a new seam beside legacy runtime state, not mutating old seam objects

## Testing Scope For PR1

### Required tests

#### New projector tests

Add tests for `LegacySessionProjector` covering:

- paused vs in-progress vs game-over status mapping
- human vs bot seat mapping
- popup visible vs no popup pending decision mapping
- debt active phase mapping

#### New service tests

Add tests for `SessionApplicationService`:

- `handle(RefreshSessionViewCommand)` returns accepted result
- returns non-null `SessionState`

### Existing regression tests

PR1 should keep existing behavior tests green.

It should not require broad test rewrites.

## Suggested PR1 File Set

This is the concrete file list target.

### New files

- `src/main/java/fi/monopoly/domain/session/SessionState.java`
- `src/main/java/fi/monopoly/domain/session/SessionStatus.java`
- `src/main/java/fi/monopoly/domain/session/SeatState.java`
- `src/main/java/fi/monopoly/domain/session/SeatKind.java`
- `src/main/java/fi/monopoly/domain/session/ControlMode.java`
- `src/main/java/fi/monopoly/domain/session/PlayerSnapshot.java`
- `src/main/java/fi/monopoly/domain/turn/TurnState.java`
- `src/main/java/fi/monopoly/domain/turn/TurnPhase.java`
- `src/main/java/fi/monopoly/domain/decision/PendingDecision.java`
- `src/main/java/fi/monopoly/domain/decision/DecisionType.java`
- `src/main/java/fi/monopoly/domain/decision/DecisionAction.java`
- `src/main/java/fi/monopoly/application/command/SessionCommand.java`
- `src/main/java/fi/monopoly/application/command/RefreshSessionViewCommand.java`
- `src/main/java/fi/monopoly/application/result/CommandResult.java`
- `src/main/java/fi/monopoly/application/result/CommandRejection.java`
- `src/main/java/fi/monopoly/application/result/DomainEvent.java`
- `src/main/java/fi/monopoly/application/result/ViewHint.java`
- `src/main/java/fi/monopoly/application/session/SessionApplicationService.java`
- `src/main/java/fi/monopoly/presentation/session/LegacySessionProjector.java`

### Modified files

Keep this list as short as possible:

- [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)
-
maybe [PopupService.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PopupService.java)
for read-only accessors

## Recommended Coding Style For PR1

- prefer records for new snapshot/result types where it stays readable
- add short class-level comments stating that the type is part of the migration seam
- do not pull new types into old `components` packages
- do not optimize prematurely for server transport serialization yet

## Review Checklist For PR1

PR1 is acceptable only if all of these are true:

- no gameplay behavior changed
- new application/domain types do not import Processing or ControlP5
- new application/domain types do not call `MonopolyRuntime.get()` or `peek()`
- `Game` remains the caller of the new seam, not the target of more rule logic
- projector is one-way:
    - legacy -> new state
    - not the other way around
- tests cover the projector’s coarse mapping choices

## Known Imperfections Accepted In PR1

These are acceptable and should not block the first PR:

- synthetic session id
- static version `0`
- coarse `TurnPhase.UNKNOWN`
- `PendingDecision.summaryText` as temporary bridge
- best-effort popup-to-decision mapping
- no authoritative mutation through commands yet

These are deliberate compromises to create the seam with low risk.

## Recommended Next Step After PR1

PR2 should be:

- explicit property purchase migration

Meaning:

- introduce `BuyPropertyCommand`
- introduce `DeclinePropertyCommand`
- turn property purchase into a true command-resolved pending decision path

That is the first PR where gameplay authority should actually move.
