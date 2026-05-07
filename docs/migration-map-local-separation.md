# Migration Map For Local UI / Game Logic Separation

## Purpose

This document maps the current codebase into the planned target architecture.

It answers four practical questions:

1. which current classes are mainly presentation, application, domain, or infrastructure
2. which current classes can survive the migration with narrower responsibilities
3. which new classes/adapters should be introduced first
4. what the first implementation PR scopes should actually be

This is intentionally not a “perfect future architecture” document. It is a migration map from the current codebase as
it exists now.

## Current High-Level Reality

The current app already has some useful extraction work, but the authority line is still wrong.

### What is already somewhat separated

- [TurnEngine.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/TurnEngine.java)
- [PropertyTurnResolver.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/PropertyTurnResolver.java)
- [InteractiveTurnEffectExecutor.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/InteractiveTurnEffectExecutor.java)
- [TradeController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/trade/TradeController.java)
- [DebtController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/DebtController.java)

These already hint at real subsystems.

### What is still structurally wrong

- [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java) still mixes:
    - Processing draw loop
    - ControlP5 button orchestration
    - authoritative gameplay flow
    - bot stepping
    - decision gating
    - game-over handling
    - view projection and caching
- [MonopolyRuntime.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/MonopolyRuntime.java) is
  still a global runtime singleton holding both UI and game-session context
- [PopupService.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PopupService.java)
  currently owns too much decision flow sequencing
- [InteractiveTurnEffectExecutor.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/InteractiveTurnEffectExecutor.java)
  still executes rule consequences by directly showing popups and branching on popup outcomes
- [TradeController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/trade/TradeController.java)
  still owns both negotiation behavior and UI flow
- [DebtController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/DebtController.java)
  still mixes debt authority and popup/UI entry

## Target Shape In Current Repo

Before any server exists, the repo should converge toward these logical areas.

### Presentation

Responsibilities:

- Processing render loop
- input capture
- local animation
- popup rendering
- button enable/disable
- view-state rendering

Current/future classes:

- [MonopolyApp.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/MonopolyApp.java)
- [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)
- [PopupService.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PopupService.java)
- all popup classes
- board/deed/image/rendering classes
- ControlP5 button wrappers

### Application

Responsibilities:

- command handling
- phase/actor validation
- orchestrating domain transitions
- bot scheduling
- command results, events, and view hints

Current/future classes:

- new `SessionApplicationService`
- new `SessionCommandHandler`
- new `CommandResult`
- new validators and state transition orchestrators
- later `BotOrchestrator`

### Domain

Responsibilities:

- authoritative session state
- Monopoly rules and transitions
- no popup/UI/runtime dependencies

Current/future classes:

- new `SessionState`, `TurnState`, `PendingDecision`, `AuctionState`, `DebtState`, `TradeState`
- refactored rule evaluators/resolvers
- parts of current property/turn/payment logic after detaching UI

### Infrastructure

Responsibilities:

- runtime bootstrap
- logging
- persistence
- later networking

Current/future classes:

- [StartMonopolyApp.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/StartMonopolyApp.java)
- [MonopolyRuntime.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/MonopolyRuntime.java)
- later server adapters and persistence

## Current Class Mapping

This section is the core migration map.

### [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)

#### Current role

- presentation shell
- orchestration
- bot runner
- command gate
- partial state authority
- popup coordinator

#### Target role

Eventually:

- presentation shell only

Keep in `Game`:

- draw/update loop
- local input collection
- animation timing
- rendering projections
- dispatching commands to application service
- local-only UI state such as hover, scroll, selected panel tab

Remove from `Game` over time:

- authoritative turn progression
- bot decision authority
- direct mutation of player/property/rent/debt/trade outcomes
- direct popup-driven branching

#### Migration note

`Game` should be treated as the main shell that becomes thinner, not as the place where new rules are added.

### [MonopolyRuntime.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/MonopolyRuntime.java)

#### Current role

- global singleton service locator
- mixes UI resources and session access

#### Target role

Split conceptually into two directions:

- UI runtime context
- application session access

#### Migration note

Do not try to delete this immediately.

Instead:

- forbid new domain/application code from depending on `MonopolyRuntime`
- introduce explicit constructor dependencies in new application/domain classes
- let `MonopolyRuntime` survive as a presentation/infrastructure bridge during migration

### [GameSession.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/GameSession.java)

#### Current role

- light session wrapper for currently active game objects

#### Target role

- probably becomes obsolete or is repurposed as a thin bridge to authoritative `SessionState`

#### Migration note

Do not invest heavily in extending current `GameSession`.

Prefer creating a new authoritative `SessionState` model beside it, then later either:

- replace `GameSession`, or
- narrow it into an application-level session holder

### [PopupService.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PopupService.java)

#### Current role

- popup instance lifecycle
- popup queue sequencing
- popup history
- decision interaction entry

#### Target role

Keep in `PopupService`:

- popup rendering lifecycle
- mapping `PendingDecisionViewState` to popup widgets
- manual/computer trigger plumbing if still needed temporarily

Remove from `PopupService` over time:

- authoritative decision sequencing
- implicit rule flow ownership
- game-state progression on close/accept/decline

#### Migration note

`PopupService` should become a presentation adapter, not an authority source.

### [InteractiveTurnEffectExecutor.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/InteractiveTurnEffectExecutor.java)

#### Current role

- bridge from turn effects into popup-based branching

#### Target role

This class should not survive long in its current form.

The long-term replacement is:

- domain/application creates `PendingDecision` or opens `DebtState` / `AuctionState`
- presentation renders those states

#### Migration note

This is one of the clearest “adapter only” classes in the current codebase.

Plan:

- stop extending this class with new behavior
- eventually replace it with a state transition adapter for the first migration slices

### [DebtController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/DebtController.java)

#### Current role

- debt authority
- payment retries
- bankruptcy branching
- popup messaging
- button visibility side effects

#### Target role

Split into:

- domain/application debt flow
- presentation debt panel / popup adapter

Keep conceptually:

- debt rules and allowed actions

Move out:

- popup messaging
- button show/hide decisions
- direct runtime/presentation coupling

#### Migration note

`DebtController` can survive temporarily as an application-facing adapter, but it should stop directly talking to popup
service and UI controls.

### [TradeController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/trade/TradeController.java)

#### Current role

- opens trade UI
- builds/reviews offers
- triggers bot trade planning
- applies trade outcomes
- routes counteroffers through popup flows

#### Target role

Split into:

- application trade orchestration
- presentation trade UI coordinator

Keep conceptually:

- trade planner and evaluator logic

Move out:

- popup flow ownership
- direct UI navigation of trade states

#### Migration note

Trade should be migrated later than property purchase/debt/auction because it is the most stateful negotiation
subsystem.

### [TurnEngine.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/TurnEngine.java)

#### Current role

- rule engine for turn execution

#### Target role

- likely one of the better starting points for domain/application extraction

#### Migration note

This class should be reviewed carefully before large refactors. It may contain the most reusable pieces of rule
sequencing once detached from UI-triggered side effects.

### [PropertyTurnResolver.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/PropertyTurnResolver.java)

#### Current role

- property landing resolution

#### Target role

- good candidate for domain-side landing resolution logic

#### Migration note

This is likely part of the first slice boundary, because property purchase and rent must stop depending on popup-owned
flow.

## Recommended New Classes And Adapters

These are the first new building blocks worth adding.

### New Authoritative Model Types

- `SessionState`
- `SessionStatus`
- `SeatState`
- `TurnState`
- `TurnPhase`
- `PendingDecision`
- `DebtStateModel`
- `AuctionStateModel`
- later `TradeStateModel`

Naming note:

Use names that do not clash badly with existing classes like current `DebtState`.

For the migration period, suffixes like `Model` or `Session` may be worth using if collisions would cause confusion.

### New Command Types

First wave only:

- `RollDiceCommand`
- `EndTurnCommand`
- `BuyPropertyCommand`
- `DeclinePropertyCommand`
- `PayDebtCommand`
- `ResolveDebtByMortgageCommand`
- `ResolveDebtBySellingBuildingCommand`
- `DeclareBankruptcyCommand`
- `PlaceAuctionBidCommand`
- `PassAuctionCommand`
- `ToggleAutoplayCommand`
- `FinishAuctionResolutionCommand`

### New Application Types

- `SessionApplicationService`
- `CommandResult`
- `CommandRejection`
- `DomainEvent`
- `ViewHint`
- `SessionViewProjector`

### Temporary Adapters

These are important because the migration will be incremental.

#### `LegacyGameStateAdapter`

Purpose:

- builds first-version `SessionState` from current live objects:
    - `Players`
    - `Board`
    - `Dices`
    - `DebtController` state
    - popup state where needed

Why:

- lets the first application/service layer coexist with current code
- avoids big-bang rewrite

#### `LegacyCommandApplier`

Purpose:

- for the first slice, translates accepted commands into current imperative operations where necessary

Why:

- first migration slices should be vertical and safe
- not every underlying rule implementation needs to be fully rewritten immediately

#### `DecisionUiAdapter`

Purpose:

- maps `PendingDecisionViewState` into current popup widgets via `PopupService`

Why:

- lets popup rendering stay mostly intact while authority moves out of popup flow

## Recommended Migration Order By Subsystem

### Step 0: Freeze The Rules Boundary

Immediate discipline rule:

- no new gameplay rules should be added directly into:
    - `Game`
    - `PopupService`
    - popup classes

All new rule changes should be forced toward future application/domain seams.

### Step 1: Introduce Command / Result / Event Infrastructure

Add:

- `CommandResult`
- `CommandRejection`
- `DomainEvent`
- `ViewHint`
- `SessionApplicationService` shell

At this step:

- it is acceptable if the service is still backed by legacy adapters

### Step 2: Add First Authoritative Session Snapshot Model

Add:

- `SessionState`
- `TurnState`
- `SeatState`
- `PendingDecision`

At this step:

- build them from current runtime/game objects
- do not yet require all rules to mutate through the new state

### Step 3: Migrate Property Purchase Vertical Slice

Goal:

- landing on purchasable property no longer depends on popup-owned branching

Introduce:

- `BuyPropertyCommand`
- `DeclinePropertyCommand`

Refactor:

- [InteractiveTurnEffectExecutor.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/InteractiveTurnEffectExecutor.java)
- [PropertyTurnResolver.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/PropertyTurnResolver.java)
- [PopupService.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PopupService.java)

Success criterion:

- property purchase decision is represented as `PendingDecision`
- UI renders it
- application command resolves it

### Step 4: Migrate Rent + Debt Opening

Goal:

- rent can open authoritative `DebtStateModel`

Refactor:

- current payment/debt path
- remove debt-entry ownership from popup flow

Success criterion:

- insufficient-rent case enters debt state without popup-owned branching

### Step 5: Migrate Debt Remediation

Goal:

- debt panel is rendering only
- debt rules sit behind commands

Refactor:

- [DebtController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/DebtController.java)
- property mortgage/building-sale entry points

Success criterion:

- debt actions are command-driven
- `PayDebtCommand` explicitly resolves debt

### Step 6: Migrate Auction Flow

Goal:

- auction becomes authoritative `AuctionStateModel`

Refactor:

- [PropertyAuctionResolver.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/PropertyAuctionResolver.java)
- current auction popup loop

Success criterion:

- current bid, leader, actor turn, passes, and terminal resolution are authoritative state

### Step 7: Add Seat Control Mode And Autoplay Command

Goal:

- autoplay is no longer a loose UI/runtime behavior

Refactor:

- bot stepping entry
  in [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)
- current turn control gating

Success criterion:

- seat control delegation exists in authoritative state
- bot actions go through the same command boundary as humans

### Step 8: Only Then Plan Trade Migration

Trade should wait until:

- command boundary exists
- pending decisions exist
- debt/auction patterns are proven

Otherwise trade migration will be much riskier.

## First PR Scope Recommendation

The first actual implementation PR should be deliberately narrow.

### Recommended PR 1

Title direction:

- `Introduce application command/result layer and authoritative pending decision model`

Scope:

- add new application/domain skeleton classes only
- add `SessionState`, `TurnState`, `PendingDecision`
- add `CommandResult`, `CommandRejection`, `DomainEvent`, `ViewHint`
- add a minimal `SessionApplicationService`
- add a projection path from current runtime objects to `SessionState`
- no large behavior change yet

Why:

- creates the seam without breaking gameplay immediately
- gives a reviewable foundation
- reduces risk of mixing refactor and behavior changes in the same PR

### Recommended PR 2

Title direction:

- `Move property purchase flow to pending decision and explicit commands`

Scope:

- `BuyPropertyCommand`
- `DeclinePropertyCommand`
- property purchase pending decision rendering through current popup UI
- remove popup-owned branching for this slice

### Recommended PR 3

- rent + debt opening

### Recommended PR 4

- debt remediation commands

### Recommended PR 5

- auction state and resolution

### Recommended PR 6

- autoplay seat control mode

## PR Size Guidance

Do not combine these in one giant branch.

Recommended ceiling:

- one vertical slice or one new foundational abstraction per PR

Bad example:

- “introduce SessionState, migrate property purchase, rewrite debt flow, and start server extraction”

Good example:

- “introduce SessionState skeleton + projection + tests”

## Test Strategy During Migration

### Keep existing behavior tests alive

Existing tests around:

- property turns
- popup layout
- debt flow
- auction flow
- bot simulations

should remain in place as regression protection.

### Add new seam tests

For each migration slice add:

- application command acceptance/rejection tests
- authoritative state transition tests
- projection tests where helpful

### Avoid UI-only assertions for new authority work

Prefer:

- “command produced pending decision of type PROPERTY_PURCHASE”

instead of:

- “specific popup text appeared”

UI tests can remain, but they should not be the primary proof of rule correctness anymore.

## Risks Specific To This Codebase

### Risk 1: Existing `DebtState` name collision

There is already a debt state class under current payment flow.

Mitigation:

- use temporary names like `DebtStateModel` or `AuthoritativeDebtState`
- rename later once legacy debt flow is gone

### Risk 2: Current runtime singleton leaks back into new code

Mitigation:

- add a hard rule for the migration:
    - new application/domain classes must not call `MonopolyRuntime.get()` or `peek()`

### Risk 3: PopupService keeps silently owning flow

Mitigation:

- any new popup work should take `PendingDecisionViewState` as input rather than encode new rule branching in popup
  callbacks

### Risk 4: `Game` continues growing during migration

Mitigation:

- treat `Game` additions as suspect by default
- if a change adds new rule branching to `Game`, stop and push it into the new seam instead

## Decisions Still Needed After This Map

These are the next design questions after the migration map.

1. What should `SessionApplicationService` API look like exactly in Java terms:
    - synchronous `apply(command) -> result`
    - or some staged update API
2. How much of current bot logic should remain temporarily view-based before full command unification?
3. Should the first `SessionState` projection be rebuilt fully every command, or partially patched?
4. Do we want a temporary bridge where `PopupService` can render either:
    - legacy popup requests
    - or new `PendingDecisionViewState`
      during the migration overlap period?

## Recommended Immediate Next Step

The next planning artifact should be a very concrete PR-1 design note:

- target package names
- exact new class names
- exact minimal fields for `SessionState`, `TurnState`, `PendingDecision`
- where the first projector/adapter lives
- what `Game` changes are allowed in PR 1 and what are explicitly out of scope

That is the smallest useful plan before implementation begins.
