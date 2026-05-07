# PR11 Design Note: Turn Continuation And Callback Elimination

## Purpose

This document defines the next implementation wave after PR9 persistence groundwork.

At this point:

- authoritative session state exists for property purchase, debt, auction, trade and turn slices
- snapshot save/load exists
- runtime restore from authoritative snapshot exists

But one major legacy dependency still remains:

- critical gameplay continuation still depends on in-memory callbacks such as `onComplete` and `onResolved`

This is the last major blocker before local state can be treated as truly reconnect-safe and backend-ready.

## Problem Statement

The current code can serialize state, but some gameplay progression still lives in transient callback chains:

- property purchase resolution resumes via `CallbackAction onComplete`
- debt resolution resumes via `CallbackAction onResolved`
- auction completion resumes via `CallbackAction onComplete`
- the interactive turn-effect executor advances via nested callbacks

This means:

- save/load is not yet fully authoritative in the middle of these flows
- restored runtime can rebuild visible subsystem state, but cannot always continue gameplay purely from saved state
- backend extraction would still inherit hidden local control flow from callback memory

In short:

state is now durable, but continuation is not.

## Goal

After PR11:

- gameplay continuation after property purchase / debt / auction no longer depends on runtime callback closures
- the next gameplay step is always recoverable from authoritative state
- restore can resume pending turn interaction and finish the turn cleanly
- `Game` and other presentation classes no longer carry authoritative continuation logic in callback form

## Non-Goals

PR11 must not:

- introduce networking yet
- redesign the visual UI
- rewrite all spot/card logic at once
- remove every `CallbackAction` in the whole project

This PR is about authoritative continuation for the migrated session slices, not total callback eradication everywhere.

## Root Cause

The true issue is not "callbacks exist".

The issue is:

- important gameplay progress still depends on opaque closures that are not representable in `SessionState`

The largest hotspot is the interactive turn resolution chain:

- landing resolves to one or more interactive effects
- each effect may wait for human/bot input
- after input, the engine must continue with the next effect or turn follow-up

That continuation currently lives in stack/callback state, not authoritative state.

## Target Model

Introduce an authoritative turn continuation state.

Suggested core idea:

- `SessionState.turnContinuation`

This object describes what still needs to happen in the current turn after the currently pending decision/debt/auction resolves.

### Suggested high-level shape

- `continuationId`
- `activePlayerId`
- `continuationType`
- `remainingInteractiveEffects`
- `completionAction`
- `contextPayload`

The exact final Java shape can still evolve, but the key rule is:

- it must be serializable
- it must be sufficient to resume progress without callback closures

## Recommended Continuation Types

### `RESUME_INTERACTIVE_EFFECTS`

Used when a spot resolution produced one or more interactive effects and the current choice/payment/auction should continue with the next effect.

Carries:

- remaining serialized effects
- any needed actor/context ids
- final completion target

### `RESUME_TURN_FOLLOW_UP`

Used when all landing effects are done and the game should continue to the normal turn follow-up phase.

Carries:

- active player id
- dice state or equivalent turn-follow-up context
- any needed `TurnResult` continuation payload

### `RESUME_AFTER_AUCTION`

This may be folded into the previous two types if the completion target is expressive enough.

It exists conceptually because property decline currently starts an auction and then resumes normal turn flow.

### `RESUME_AFTER_DEBT`

Likewise this may be folded into general continuation state, but conceptually:

- debt opening pauses progress
- after debt resolves, the original turn/spot flow must continue

## Interactive Effect State

To remove executor callback ownership, interactive effects need a serializable form.

Suggested model:

- `InteractiveEffectState`
  - `effectType`
  - typed payload

Initial effect types likely needed:

- `SHOW_MESSAGE`
- `ADJUST_PLAYER_MONEY`
- `OFFER_PROPERTY_PURCHASE`
- `PAY_RENT`

These correspond directly to the currently migrated property/turn interaction path.

## Completion Target

The continuation should not store arbitrary code references.

Instead it should store a small declarative completion target, for example:

- `NONE`
- `RESUME_INTERACTIVE_EFFECTS`
- `APPLY_TURN_FOLLOW_UP`
- `SHOW_END_TURN`
- `END_TURN_WITH_SWITCH`
- `END_TURN_WITHOUT_SWITCH`

This is the replacement for opaque callback intent.

## Recommended Migration Strategy

### Step 1

Add authoritative continuation types to domain/session and snapshot mapping.

Do not wire behavior yet.

Purpose:

- establish serializable shape
- lock terminology
- enable save/load roundtrip tests

### Step 2

Refactor property purchase flow to set continuation state instead of relying on `onComplete`.

Expected result:

- accept purchase clears decision and resumes continuation from state
- decline purchase opens auction and preserves the continuation target for post-auction resume

### Step 3

Refactor auction flow to finish into continuation state instead of invoking `onComplete`.

Expected result:

- `FinishAuctionResolutionCommand` consumes auction state
- then resumes the saved continuation target

### Step 4

Refactor debt opening/remediation flow to finish into continuation state instead of invoking `onResolved`.

Expected result:

- once debt is paid or bankruptcy handled, the authoritative continuation decides what happens next

### Step 5

Move interactive turn-effect execution from recursive callback chain into a small state machine:

- derive interactive effect states
- consume one state at a time
- persist remaining queue in `SessionState`

This is the real completion of callback elimination for migrated turn interactions.

## Expected Code Impact

Likely touched areas:

- `SessionState`
- `SessionSnapshot`
- `SessionSnapshotMapper`
- `SessionApplicationService`
- `PropertyPurchaseCommandHandler`
- `AuctionCommandHandler`
- `RentAndDebtOpeningHandler`
- `DebtRemediationCommandHandler`
- `GameTurnFlowCoordinator`
- `InteractiveTurnEffectExecutor`

Likely new types:

- `TurnContinuationState`
- `TurnContinuationType`
- `InteractiveEffectState`
- `InteractiveEffectType`
- typed continuation payload records

## Restore Requirement

PR11 is complete only if this becomes true:

- saving during property purchase, auction, or debt
- restoring
- resuming command processing

must continue gameplay correctly without depending on the original JVM callback closures.

That is the real acceptance bar.

## Tests Required

### Unit tests

- continuation snapshot roundtrip
- property purchase continuation transition
- auction completion continuation transition
- debt resolution continuation transition

### Integration tests

- save/load during property purchase and continue turn
- save/load during auction and continue turn
- save/load during debt resolution and continue turn

### Regression tests

- no duplicate continuation execution
- no callback-only state required for command handling after restore

## Why This PR Matters

Without PR11:

- PR9 save/load is only partially authoritative
- PR10 backend extraction would still inherit hidden local control flow

With PR11:

- turn progression becomes truly data-driven
- restore and reconnect semantics become coherent
- backend authority becomes much safer to implement

## Recommended Next Step After PR11

After PR11, backend extraction becomes reasonable to start in earnest.

At that point the major remaining work is mostly transport/session-hosting work, not hidden control-flow archaeology.
