# Architecture Separation And Server Plan

## Purpose

This document defines a practical migration path from the current single-process Processing app to a split architecture
where:

- UI is separated from game rules
- game rules are driven through commands and authoritative state
- the same application logic can later run inside a backend server
- human, autoplay, bot-only, and online multiplayer modes can share the same core model

This is intentionally migration-first, not greenfield-perfect. The goal is to avoid rewriting Monopoly rules twice.

## Current Reality

The current codebase already contains useful partial separation:

- `TurnEngine`, `PropertyTurnResolver`, `InteractiveTurnEffectExecutor`, trade/debt controllers, and bot evaluators
  already describe meaningful application behavior
- `GameSession` is a start toward a session-level runtime object
- recent work has reduced `Game` size somewhat by extracting trade/debt/debug controllers

But the current system is still not safely portable to a server because:

- `Game` still mixes render loop, input, orchestration, and authoritative gameplay flow
- `MonopolyRuntime` is still a global runtime/singleton with UI dependencies
- several domain-ish objects still call UI/popup/runtime helpers directly
- popup flow is represented as UI widgets instead of authoritative pending decisions
- bot behavior is still coupled to current client-facing view models and runtime assumptions

## Main Recommendation

Do not start by building a network server.

Start by separating the current app locally into:

1. `domain`
2. `application`
3. `presentation`
4. `infrastructure`

Then move `application + domain` behind a server boundary later.

This is the lowest-risk path and keeps multiplayer, autoplay, and save/resume aligned.

## Target Architecture

### 1. Domain Layer

Responsibilities:

- pure Monopoly rules
- authoritative session state
- state transitions from commands
- no UI classes
- no Processing types
- no ControlP5
- no runtime singletons

Examples of future domain concepts:

- `SessionState`
- `TurnState`
- `PlayerState`
- `BoardState`
- `PropertyState`
- `DebtResolutionState`
- `TradeState`
- `PendingDecision`
- `PlayerCommand`
- `DomainEvent`

### 2. Application Layer

Responsibilities:

- session orchestration
- command validation
- command sequencing
- bot scheduling
- save/load
- session lifecycle
- mapping domain events to client-facing state updates

Examples:

- `GameApplicationService`
- `SessionCommandHandler`
- `BotOrchestrator`
- `SessionPersistenceService`

### 3. Presentation Layer

Responsibilities:

- render board and panels
- render popup/decision UI
- handle local input
- animate approved state transitions
- show autoplay, spectator, and local human states

Current Processing app should eventually become this layer.

### 4. Infrastructure Layer

Responsibilities:

- WebSocket / HTTP
- PostgreSQL persistence
- serialization
- logging
- external process/runtime setup

This layer should know about application APIs, not Monopoly rules directly.

## Migration Rule

Every gameplay action should eventually follow this shape:

1. UI captures intent
2. UI sends `PlayerCommand`
3. application validates and applies the command
4. domain returns new authoritative state + events
5. UI renders resulting state

Rule of thumb:

- UI proposes
- application/domain decides

## State Model To Design First

The first important design artifact should be a new authoritative state model.

### Proposed `SessionState`

Contains:

- session id
- lifecycle status
- board state
- players
- current turn owner
- current phase
- dice state
- pending decision
- debt state
- trade negotiation state
- bankruptcy state
- winner state
- deck state
- eliminated players
- session version

### Proposed `TurnPhase`

Examples:

- `WAITING_FOR_ROLL`
- `ANIMATING_MOVE`
- `RESOLVING_SPOT`
- `WAITING_FOR_DECISION`
- `RESOLVING_DEBT`
- `WAITING_FOR_END_TURN`
- `GAME_OVER`

The exact enum names are flexible, but the important point is that phase becomes authoritative state rather than UI
inference.

### Proposed `PendingDecision`

This is the key abstraction replacing UI-owned popup logic.

Examples:

- property purchase choice
- jail choice
- debt follow-up choice
- auction bid/pass
- trade accept/reject/counter
- generic ok/continue prompt

It should contain at least:

- `decisionId`
- `actorPlayerId`
- `decisionType`
- `payload`
- `allowedOptions`

Important:

the server/domain should own the fact that a decision is pending, while the UI decides how to render it.

## Command Model

The system should converge toward explicit commands such as:

- `RollDiceCommand`
- `EndTurnCommand`
- `BuyPropertyCommand`
- `DeclinePropertyCommand`
- `RespondToDecisionCommand`
- `RetryDebtPaymentCommand`
- `DeclareBankruptcyCommand`
- `SellBuildingCommand`
- `BuyBuildingRoundCommand`
- `MortgagePropertyCommand`
- `UnmortgagePropertyCommand`
- `ProposeTradeCommand`
- `RespondToTradeCommand`
- `ToggleAutoplayCommand`

Each command should be attributable to:

- session id
- actor id
- command id
- payload

Later, the same command objects can cross the network with very small adaptation.

## Presentation Modes To Support

This project should not optimize only for current single-window local play.

The client should be planned around 3 main modes:

### Interactive Mode

- local user controls one seat
- only relevant actions are enabled
- other players are observed

### Autoplay / Assist Mode

- local user owns a seat but delegates it to a bot temporarily
- useful for testing, accessibility, and “play for me for a while”
- ideally can be toggled per local seat

### Spectator Mode

- no local action authority
- user only observes
- useful for remote multiplayer while waiting, reconnect states, or later spectating

These modes are mostly UI/application concerns, not rule concerns.

## Human-Only UI Direction

When the active local player is a human seat:

- keep current rich controls
- surface actionable context clearly
- allow autoplay toggle for the current seat if desired

When the active local player is not the local human:

- hide or disable gameplay-changing controls
- show state clearly, not “fake available actions”
- optionally show “Autoplay active for your seat” if local seat is delegated

For online play this means:

- local human sees command options only when `actorPlayerId == localSeatId`
- otherwise UI becomes observer-focused

## Recommended View Model Direction

Presentation should not read domain objects directly.

Introduce client-facing view state types such as:

- `SessionViewState`
- `BoardViewState`
- `PlayerPanelViewState`
- `AvailableActionsViewState`
- `PendingDecisionViewState`
- `AnimationHints`

The Processing UI should render those.

This has two benefits:

- domain can stay server-safe
- client can evolve later without touching rules

## Options For Separation

### Option A: Thin Application Layer Inside Current Module First

Approach:

- keep one Gradle/Maven module for now
- introduce domain/application packages
- migrate logic incrementally
- keep Processing app calling application APIs in-process

Pros:

- lowest disruption
- fastest feedback
- least setup overhead

Cons:

- weaker physical separation early
- easier to accidentally leak UI references back into domain

### Option B: Multi-Module Split Early

Approach:

- create modules now:
    - `monopoly-domain`
    - `monopoly-application`
    - `monopoly-client-processing`

Pros:

- stronger architecture enforcement
- easier future server extraction

Cons:

- higher short-term mechanical cost
- more build/tooling churn early

### Recommended Choice

Start with Option A for the first refactor wave, but structure it so it can become Option B without rewriting package
boundaries.

That means:

- use package boundaries now as if modules already existed
- avoid UI imports in new domain/application code
- move to multi-module once command/state boundaries are stable

## Server Migration Strategy

When local separation is far enough, move to:

- `monopoly-domain`
- `monopoly-application`
- `monopoly-server`
- `monopoly-client-processing`

The server should own:

- authoritative session state
- command processing
- bots
- save/load
- broadcasting state snapshots or diffs

The client should own:

- rendering
- local animation
- sending commands
- reconnect UX

## What Should Be Migrated First

### Phase 1: Define The Contract

Deliverables:

- `SessionState` draft
- `PlayerCommand` draft
- `PendingDecision` draft
- `DomainEvent` draft

No rendering changes required yet.

### Phase 2: Property Purchase / Rent / Popup Decisions

Goal:

- migrate one full vertical slice from UI-driven flow to command/state-driven flow

Suggested slice:

- land on property
- offer buy / decline
- rent payment
- pending decision state instead of popup-owned branching

This slice is foundational and touches many common patterns.

### Phase 3: Debt / Bankruptcy

Goal:

- debt state becomes authoritative state, not “UI currently open”

This is important for:

- save/resume
- bots
- networking

### Phase 4: Trade Flow

Trade is one of the most stateful subsystems and should become explicit negotiation state.

Trade should eventually become:

- `TradeState`
- `TradeProposal`
- `TradeResponse`
- `TradeCounterOffer`

### Phase 5: Bot API Unification

Bots should stop “thinking in UI”.

They should:

- inspect authoritative state
- produce commands through the same application boundary as humans

### Phase 6: Presentation Cleanup

Only after the above, Processing UI should stop calling gameplay methods directly and render from view state.

### Phase 7: Persistence

Once authoritative state exists, snapshot save/load becomes straightforward.

### Phase 8: Server Extraction

Only here should the server boundary become a major implementation task.

## Estimated Complexity

### Separation Only

Large but manageable. This is several major refactor tasks, not a rewrite.

### Separation + Save/Resume

Large.

### Separation + Networking MVP

Very large. This is likely the first truly multi-phase roadmap milestone.

## Biggest Risks

### Risk 1: UI Side Effects Hidden In Domain-ish Classes

Examples:

- property classes showing popups
- runtime lookups from game logic

Mitigation:

- forbid new UI dependencies in domain/application code immediately
- add TODO markers where runtime/popup dependencies still exist

### Risk 2: Popup Flow Is Actually Decision Flow

Today many gameplay branches are represented as popup widgets.

Mitigation:

- replace popup-owned branching with authoritative `PendingDecision`

### Risk 3: `Game` Remains A God Object During Migration

Mitigation:

- after contract definition, shrink `Game` aggressively toward presentation shell responsibilities only

### Risk 4: Networking Too Early

Mitigation:

- do not build server transport before command/state model is stable

## Proposed Large Tasks

These are the large implementation tasks this plan should eventually be split into:

1. `Define authoritative SessionState, PlayerCommand, PendingDecision, and DomainEvent model`
2. `Introduce local application service that owns command handling and authoritative state transitions`
3. `Migrate property purchase, rent, and popup decisions to command/state flow`
4. `Migrate debt and bankruptcy flow to authoritative state`
5. `Migrate trade flow to explicit negotiation state`
6. `Unify bot actions behind the same command API used by human players`
7. `Adapt Processing UI to render from view state and dispatch commands only`
8. `Add UI play modes: interactive, autoplay, spectator`
9. `Add snapshot save/load on authoritative session state`
10. `Split project into domain/application/client modules`
11. `Build backend service with WebSocket session sync`

## Open Design Questions

These are the places where user/product opinion matters and should be decided before implementation starts:

1. Should autoplay be:
    - a temporary per-turn helper
    - a persistent per-seat mode
    - both

2. In online play, do you want:
    - strict one-seat-per-human only
    - or one client controlling multiple local seats in a private game

3. Should the MVP networking model use:
    - full state snapshot after every command
    - or snapshot + lightweight event stream from the start

4. Should save/resume include mid-decision states immediately in MVP:
    - yes is recommended
    - but it increases migration strictness early

5. Do you want the Processing client to remain the long-term client:
    - yes
    - or only as an interim client until a later different frontend exists

## Recommended Immediate Next Step

The next design artifact should be a concrete spec for:

- `SessionState`
- `PlayerCommand`
- `PendingDecision`
- `DomainEvent`

That document should include fields, ownership rules, and examples for:

- property purchase
- rent payment
- debt resolution
- trade
- auction

Once that exists, implementation can begin in controlled slices instead of broad refactors.
