# PR3 Design Note: Rent And Debt Opening

## Purpose

This document defines the third migration PR after:

- PR1: session-state seam
- PR2: property purchase slice

PR3 should migrate exactly this behavior boundary:

- player lands on owned property
- rent is calculated
- payment is attempted
- if payment succeeds, turn continues
- if payment cannot be paid immediately, authoritative debt state is opened

PR3 does not yet migrate full debt remediation behavior. It only moves debt opening and debt authority start into the
new state model.

## PR3 Goal

After PR3:

- rent payment outcome is no longer controlled by popup-owned branching
- immediate payment vs debt opening is decided by application/domain logic
- when debt is required, authoritative debt state exists in `SessionState`
- UI renders debt state instead of owning debt flow
- current debt remediation buttons and actions may still be legacy-backed temporarily

This PR is about opening debt correctly, not fully solving debt through the new architecture yet.

## Explicit Non-Goals For PR3

PR3 must not:

- migrate debt remediation commands fully
- migrate bankruptcy asset transfer fully
- migrate auction resolution fully
- migrate jail/debt interactions outside rent/payment opening
- redesign the debt UI completely
- migrate trade/deed management

If a change belongs to “how debt is resolved after it is open”, that mostly belongs to PR4.

## Required Preconditions

PR3 assumes:

- PR1 seam exists
- PR2 property purchase flow exists
- `SessionState` can already carry:
    - `PendingDecision`
    - `AuctionState`
- commands/results/events/view hints exist

PR3 will extend `SessionState` with debt authority.

## Exact New Types For PR3

### Domain-side debt state

#### `fi.monopoly.domain.session.DebtStateModel`

Use `DebtStateModel` name first to avoid collision with
current [DebtState.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/DebtState.java).

PR3 minimal fields:

- `String debtId`
- `String debtorPlayerId`
- `DebtCreditorType creditorType`
- `String creditorPlayerId`
- `int amountRemaining`
- `String reason`
- `boolean bankruptcyRisk`
- `List<DebtAction> allowedActions`

Notes:

- keep it narrow
- do not include full remediation history yet
- the key point is authoritative visibility of debt being active

#### `fi.monopoly.domain.session.DebtCreditorType`

PR3 values:

- `PLAYER`
- `BANK`

#### `fi.monopoly.domain.session.DebtAction`

PR3 values:

- `PAY_DEBT_NOW`
- `MORTGAGE_PROPERTY`
- `SELL_BUILDING`
- `DECLARE_BANKRUPTCY`

Notes:

- this does not mean all actions are migrated in PR3
- it just tells the UI and future command system what kind of remediation is conceptually allowed

### Domain-side payment snapshot

#### `fi.monopoly.domain.session.PaymentObligation`

PR3 minimal fields:

- `String debtorPlayerId`
- `DebtCreditorType creditorType`
- `String creditorPlayerId`
- `int amount`
- `String reason`

Purpose:

- cleanly model “what must be paid” separate from “how debt is being resolved”

This may live inside `DebtStateModel`, but it is worth defining conceptually now.

### Commands

PR3 does not need the full remediation command set yet, but it does need one explicit debt payment command because that
decision was already locked earlier.

#### `fi.monopoly.application.command.PayDebtCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String debtId`

Notes:

- PR3 may still keep this only lightly wired if the actual remediation remains legacy-backed
- but the command type should already exist

## Existing Types To Extend In PR3

### `SessionState`

Add:

- `DebtStateModel activeDebt`

### `TurnPhase`

Ensure it supports:

- `RESOLVING_DEBT`

### `DomainEvent`

PR3 will require at least these event types in practice:

- `RentCharged`
- `DebtOpened`
- `DebtResolved`

They may still be represented through the generic PR1 `DomainEvent` shape if typed events are not introduced yet.

## Current Legacy Flow To Replace

Current path:

- [PropertyTurnResolver.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/PropertyTurnResolver.java)
  emits `PayRentEffect`
- [InteractiveTurnEffectExecutor.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/InteractiveTurnEffectExecutor.java)
  shows rent popup
- callback calls payment handler
- [DebtController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/DebtController.java)
  decides whether debt state opens
- popup/UI flow still strongly shapes user experience and authority

Problem:

- debt opening is still downstream of popup callbacks and UI sequencing

Target after PR3:

- rent obligation is determined by application/domain flow
- immediate payment vs debt opening is authoritative
- UI renders whichever state results

## Recommended Transitional Architecture

### New application component

#### `RentAndDebtOpeningHandler`

Responsibilities:

- accept a rent obligation from the landing resolution path
- decide immediate payment vs debt opening
- update `SessionState`
- produce `DomainEvent`s and `ViewHint`s

This should not own remediation commands yet.

### New legacy bridge

#### `LegacyPaymentGateway`

Responsibilities:

- wrap
  current [PaymentResolver.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/PaymentResolver.java)
- expose:
    - attempt immediate payment
    - derive whether debt is required
    - derive bankruptcy risk

Potential methods:

- `PaymentOutcome tryResolve(PaymentObligation obligation)`
- `void applyImmediatePayment(...)`

Why:

- keeps PR3 from rewriting payment math
- localizes reliance on current player/property classes

### New presentation bridge

#### `DebtViewAdapter`

Purpose:

- map `DebtStateModel` into the current debt UI surface

Responsibilities in PR3:

- show debt state as active
- keep current legacy remediation controls alive

Important:

- debt UI may still call some legacy debt actions temporarily in PR3
- but opening debt must now be driven by authoritative `DebtStateModel`

## Exact State Mapping Rules

### Rent Payment Immediate Success

When rent is due and debtor has enough liquid cash:

Mutations:

- subtract rent from debtor
- add rent to creditor
- `activeDebt = null`
- `turn.phase` advances to next normal post-landing phase

Events:

- `RentCharged`

View hints:

- show rent summary
- close/avoid debt UI

### Rent Opens Debt

When rent is due and debtor cannot pay immediately but can still raise enough funds:

Mutations:

- create `DebtStateModel`
- set:
    - debtor
    - creditor
    - amount remaining
    - reason
    - bankruptcy risk
    - allowed actions
- `turn.phase = RESOLVING_DEBT`

Events:

- `RentCharged`
- `DebtOpened`

View hints:

- open debt panel
- focus debtor assets

### Rent Causes Immediate Bankruptcy Risk

When debtor cannot pay immediately and total liquidatable value is insufficient:

Mutations:

- still create `DebtStateModel`
- set `bankruptcyRisk = true`
- keep `turn.phase = RESOLVING_DEBT`

Reason:

- bankruptcy remains a user/bot action decision
- it should not silently happen without going through debt state first

This matches current behavior better and keeps the command model consistent.

## Interaction With Legacy DebtController

This is the key transitional question in PR3.

### Recommendation

Do not delete or
rewrite [DebtController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/DebtController.java)
in PR3.

Instead:

- stop letting it be the authority for “whether debt exists”
- let it temporarily act as a remediation executor / legacy helper once debt is already open

Meaning:

- `SessionState.activeDebt` becomes authoritative
- existing debt UI actions can still delegate to legacy debt operations for one PR longer if needed

This keeps PR3 narrow and makes PR4 much safer.

### Practical transitional model

- PR3 opens `DebtStateModel`
- UI sees debt is active from new state
- debt panel is shown based on new state
- specific debt actions may still be routed into legacy `DebtController` temporarily
- after each legacy action, the new projector/service must re-project and keep `activeDebt` in sync

This is not ideal final architecture, but it is a good migration step.

## Required Current-Class Changes

### [PropertyTurnResolver.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/PropertyTurnResolver.java)

PR3 target:

- keep rent calculation here if practical
- but stop letting rent popup callback own debt opening

Recommendation:

- continue producing a rent-oriented turn effect
- but route the follow-up into application-side payment/debt opening handler instead of directly through popup-owned
  payment branching

### [InteractiveTurnEffectExecutor.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/InteractiveTurnEffectExecutor.java)

PR3 target:

- for `PayRentEffect`, stop owning the decisive branch “paid immediately vs open debt”
- it may still show message UI if needed, but authority should live outside popup callback flow

Strong recommendation:

- rent popup should become a projection of `RentCharged` / `DebtOpened`, not the trigger for those decisions

### [DebtController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/DebtController.java)

PR3 target:

- no longer owns the authoritative existence of debt
- may still temporarily execute remediation side effects
- should be read as a legacy helper, not the new source of truth

### [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)

Allowed in PR3:

- render debt UI when `SessionState.activeDebt != null`
- ask application/service layer for current authoritative debt snapshot
- refresh UI after payment/debt-opening results

Not allowed:

- new debt rules in `Game`
- new direct payment/debt branching in `Game`

## Bot Behavior In PR3

PR3 does not need full debt remediation command migration yet.

But bots must at least react consistently to the authoritative debt-open state.

Required:

- if debt opens for a bot, the system should not rely on a popup callback path to know the bot is in debt
- bot-facing debt visibility should come from `SessionState.activeDebt`

Temporary allowance:

- the bot may still rely on legacy remediation heuristics in PR3
- full command unification for debt can wait for PR4

## Tests Required For PR3

### Application-level tests

Add tests for:

- rent obligation with sufficient cash -> immediate payment, no debt
- rent obligation with insufficient cash but enough liquidation value -> debt opens
- rent obligation with insufficient total liquidation value -> debt opens with bankruptcy risk true

### Projection / integration tests

Add tests for:

- `SessionState.activeDebt` projection causes debt UI visibility
- rent flow no longer depends on popup callback to create debt state

### Existing regressions to keep green

- property turn resolver tests
- debt tests
- turn control tests
- bot simulation tests if touched

## Exact Files Likely Added In PR3

- `src/main/java/fi/monopoly/domain/session/DebtStateModel.java`
- `src/main/java/fi/monopoly/domain/session/DebtCreditorType.java`
- `src/main/java/fi/monopoly/domain/session/DebtAction.java`
- `src/main/java/fi/monopoly/domain/session/PaymentObligation.java`
- `src/main/java/fi/monopoly/application/command/PayDebtCommand.java`
- `src/main/java/fi/monopoly/application/session/RentAndDebtOpeningHandler.java`
- `src/main/java/fi/monopoly/presentation/session/LegacyPaymentGateway.java`
- `src/main/java/fi/monopoly/presentation/session/DebtViewAdapter.java`

## Exact Files Likely Modified In PR3

- [PropertyTurnResolver.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/PropertyTurnResolver.java)
- [InteractiveTurnEffectExecutor.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/InteractiveTurnEffectExecutor.java)
- [DebtController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/DebtController.java)
- [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)
-
maybe [PaymentResolver.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/PaymentResolver.java)
only if a safer adapter seam requires it

## Review Checklist For PR3

PR3 is acceptable only if:

- debt existence is represented in authoritative `SessionState`
- immediate payment vs debt opening is not decided by popup callback anymore
- bankruptcy risk becomes authoritative debt-state data
- `DebtController` is visibly moving toward helper/executor status rather than authority
- no full remediation migration was accidentally bundled in

## Main Risk In PR3

The main risk is partial double authority:

- legacy `DebtController` says one thing
- new `SessionState.activeDebt` says another

Mitigation:

- choose one rule and enforce it in code review:
    - `SessionState.activeDebt` is the source of truth
    - legacy debt controller is only a helper during transition

## Recommended Next Step After PR3

PR4 should be:

- debt remediation commands

That is where:

- mortgage
- building sale
- explicit `PayDebtCommand`
- bankruptcy declaration

move behind the same command boundary.
