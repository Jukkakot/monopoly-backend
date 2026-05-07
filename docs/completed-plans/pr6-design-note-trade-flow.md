# PR6 Design Note: Trade Flow Migration

## Purpose

This document defines the sixth migration PR after:

- PR1: session-state seam
- PR2: property purchase slice
- PR3: rent and debt opening
- PR4: debt remediation
- PR5: auction flow

PR6 should migrate the trade subsystem from UI/controller-owned flow to authoritative state + commands.

This is the first large negotiation-oriented subsystem in the migration.

## PR6 Goal

After PR6:

- an active trade negotiation is represented as authoritative `TradeState`
- the current trade offer is authoritative state
- trade editing happens through commands
- trade submit / accept / decline / counter happen through commands
- current visual trade popup may remain largely intact, but it becomes a renderer/editor of `TradeState`
- `TradeController` is no longer the authority for negotiation progression

This PR should complete the core trade loop, even if some UX polish remains later.

## Explicit Non-Goals For PR6

PR6 must not:

- redesign the current trade visuals from scratch
- redesign trade evaluator heuristics broadly
- redesign proactive bot trade strategy broadly
- change trade balance rules unless needed for authoritative consistency
- migrate unrelated deed UI or build/mortgage UX outside trade context

If a change does not move trade authority into state + commands, it is out of scope.

## Required Preconditions

PR6 assumes:

- pending decision infrastructure already exists
- command/result/event infrastructure exists
- bots can already use explicit commands in earlier subsystems
- auction/debt are already sufficiently authoritative that trade no longer depends on popup-owned control patterns

## Core Design Choice For PR6

The current visual trade system is strong enough to preserve initially.

Recommendation:

- keep the current trade popup/editor visual language
- migrate only the authority model underneath it

That means:

- [TradePopup.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/TradePopup.java)
  can remain a presentation component
- [TradeUiBuilder.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/trade/TradeUiBuilder.java)
  can remain a presentation helper
-
but [TradeController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/trade/TradeController.java)
must stop owning negotiation authority

## Exact New / Finalized Types For PR6

### `TradeState`

Recommended package:

- `fi.monopoly.domain.session.TradeState`

Fields:

- `String tradeId`
- `String initiatorPlayerId`
- `String recipientPlayerId`
- `TradeStatus status`
- `TradeOfferState currentOffer`
- `String editingPlayerId`
- `String decisionRequiredFromPlayerId`
- `String openedByPlayerId`
- `List<TradeHistoryEntry> history`

Notes:

- `editingPlayerId` is important because current UI allows switching whose side is being edited
- `decisionRequiredFromPlayerId` makes accept/decline/counter authority explicit

### `TradeStatus`

Suggested values:

- `EDITING`
- `SUBMITTED`
- `COUNTERED`
- `ACCEPTED_PENDING_APPLY`
- `DECLINED`
- `CANCELLED`

Notes:

- `ACCEPTED_PENDING_APPLY` may look redundant, but it gives room for atomic validation/application and later
  animation/view hints

### `TradeOfferState`

Recommended package:

- `fi.monopoly.domain.session.TradeOfferState`

Fields:

- `String proposerPlayerId`
- `String recipientPlayerId`
- `TradeSelectionState offeredToRecipient`
- `TradeSelectionState requestedFromRecipient`

### `TradeSelectionState`

Fields:

- `int moneyAmount`
- `List<String> propertyIds`
- `int jailCardCount`

Notes:

- use ids and counts, not live property/player objects
- keep it authoritative and transport-safe

### `TradeHistoryEntry`

Fields:

- `String actorPlayerId`
- `String actionType`
- `String summary`

This is acceptable as lightweight projection-supporting history.

Later it can become more structured if needed.

## Commands For PR6

### `OpenTradeCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String recipientPlayerId`

Validation:

- actor is active player
- no incompatible blocking state:
    - no game over
    - no unresolved debt for actor
    - no existing active trade

### `EditTradeOfferCommand`

This was already chosen as the initial generic editing model.

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String tradeId`
- `TradeEditPatch patch`

### `TradeEditPatch`

Recommended fields:

- `Boolean editOfferedSide`
- `Integer replaceMoneyAmount`
- `List<String> propertyIdsToAdd`
- `List<String> propertyIdsToRemove`
- `Boolean toggleJailCard`
- `Boolean switchEditingSide`

Notes:

- keep the patch intentionally narrow and explicit
- do not try to make it a general-purpose JSON patch abstraction

### `SubmitTradeOfferCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String tradeId`

Validation:

- actor is allowed to submit current offer
- offer is non-empty and valid

### `AcceptTradeCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String tradeId`

Validation:

- actor equals `decisionRequiredFromPlayerId`
- trade is in a submitted/countered response state
- current offer is still valid against real ownership/cash/jail-card state

### `DeclineTradeCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String tradeId`

### `CounterTradeCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String tradeId`

Notes:

- this command should not separately carry a full new offer
- the expectation is:
    - actor edits authoritative `TradeState.currentOffer`
    - then issues `CounterTradeCommand`

### `CancelTradeCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String tradeId`

Notes:

- allows backing out of editing/negotiation cleanly

## Existing Types To Reuse Or Retire

### [TradeDraft.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/trade/TradeDraft.java)

Target after PR6:

- should become presentation-local editor helper at most
- should not be the authority for current negotiation state

### [TradeOffer.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/trade/TradeOffer.java)

Target after PR6:

- may survive temporarily as a legacy helper for validation/application
- but authoritative state should be `TradeOfferState`

### [TradeSelection.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/trade/TradeSelection.java)

Target after PR6:

- may survive temporarily as a presentation/helper type
- but authoritative trade state should use property ids, money, jail-card counts rather than live `Property` objects

### [TradeController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/trade/TradeController.java)

Target after PR6:

- stop owning trade progression and application
- become closer to:
    - trade view launcher
    - trade command dispatcher
    - proactive bot proposal adapter

## Recommended Transitional Architecture

### New application component

#### `TradeCommandHandler`

Responsibilities:

- validate all trade commands
- mutate `TradeState`
- apply accepted trades atomically
- emit trade events
- produce view hints

This should become the authority for trade progression.

### New legacy bridge

#### `LegacyTradeGateway`

Responsibilities:

- validate ownership/cash/jail-card constraints using current player/property objects
- apply accepted trade atomically through current player/property methods
- convert between legacy `TradeOffer`/`TradeSelection` helpers and authoritative `TradeOfferState` as needed

Why:

- lets PR6 reuse current low-level trade application code safely
- avoids rewriting all trade asset transfer logic in one go

### Presentation bridge

#### `TradeViewAdapter`

Responsibilities:

- map `TradeState` into existing trade popup/editor UI
- dispatch edit/submit/accept/decline/counter/cancel commands
- preserve current visual trade UX where practical

Important:

- the popup/editor may stay rich and visual
- it must not own trade authority anymore

## Exact State Transition Rules

### Open trade

Command:

- `OpenTradeCommand`

Mutations:

- create `TradeState`
- `status = EDITING`
- `initiatorPlayerId = actor`
- `recipientPlayerId = target`
- `editingPlayerId = actor`
- `decisionRequiredFromPlayerId = null`
- `currentOffer = empty symmetric offer`

Events:

- `TradeOpened`

View hints:

- open trade editor

### Edit trade

Command:

- `EditTradeOfferCommand`

Mutations:

- apply patch to authoritative `TradeState.currentOffer`
- optionally change `editingPlayerId`

Events:

- `TradeOfferUpdated`

View hints:

- refresh trade editor

### Submit trade

Command:

- `SubmitTradeOfferCommand`

Validation:

- offer is not empty
- all selected properties/cards/cash amounts are valid at submit time

Mutations:

- `status = SUBMITTED`
- `decisionRequiredFromPlayerId = recipientPlayerId`
- `editingPlayerId = recipientPlayerId`

Events:

- `TradeOfferSubmitted`

View hints:

- switch to trade review/response UI

### Accept trade

Command:

- `AcceptTradeCommand`

Mutations:

- set `status = ACCEPTED_PENDING_APPLY`
- apply trade atomically through gateway
- clear active `TradeState`

Events:

- `TradeAccepted`

View hints:

- close trade UI
- refresh ownership/cash panels

### Decline trade

Command:

- `DeclineTradeCommand`

Mutations:

- clear active `TradeState`

Events:

- `TradeDeclined`

View hints:

- close trade UI or show declined summary

### Counter trade

Command:

- `CounterTradeCommand`

Validation:

- actor equals `decisionRequiredFromPlayerId`
- current authoritative offer is valid and non-empty

Mutations:

- `status = COUNTERED`
- swap `decisionRequiredFromPlayerId` to the other party
- set `editingPlayerId` to the other party

Events:

- `TradeCountered`

View hints:

- switch review/editor ownership to the other side

### Cancel trade

Command:

- `CancelTradeCommand`

Mutations:

- clear active `TradeState`

Events:

- `TradeCancelled`

View hints:

- close trade UI

## Human And Bot Behavior In PR6

### Human

Human uses the visual trade editor as before, but all actions dispatch commands.

Meaning:

- selecting assets edits authoritative trade state
- accept/decline/counter are commands
- no popup callback should directly apply trade anymore

### Bot

Required:

- proactive trade proposals should materialize as `OpenTradeCommand` + edit/submit sequence, or a direct
  application-level open+populate flow that still yields authoritative `TradeState`
- trade responses from bots must go through:
    - `AcceptTradeCommand`
    - `DeclineTradeCommand`
    - `CounterTradeCommand`

Temporary allowance:

- current `StrongTradePlanner` and evaluator heuristics may still be reused
- but they should read projected state and execute via commands

## `TradeController` Migration Rule

PR6 should
make [TradeController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/trade/TradeController.java)
stop being the negotiation authority.

After PR6 it may still:

- launch trade UI
- coordinate view adapters
- host proactive bot trade initiation entry points

But it should not:

- own authoritative offer state
- directly apply accepted trades
- directly decide counter/decline/accept branching as the source of truth

## Current-Class Change Guidance

### [TradeUiBuilder.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/trade/TradeUiBuilder.java)

Allowed:

- remain a presentation helper
- build popup view models from authoritative `TradeState`

Not allowed:

- own trade progression decisions

### [TradePopup.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/TradePopup.java)

Allowed:

- continue to render the rich trade editor/review UI

Not allowed:

- directly apply trade rule outcomes

### [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)

Allowed:

- open trade UI based on current authoritative state
- dispatch commands

Not allowed:

- host new trade rule logic

## Tests Required For PR6

### Application-level tests

Add tests for:

- open trade creates `TradeState`
- edit trade patch updates current offer correctly
- submit rejects empty trade
- submit rejects invalid ownership/cash/jail-card state
- accept applies valid trade and clears trade state
- decline clears trade state
- counter flips `decisionRequiredFromPlayerId`
- cancel clears trade state

### Integration tests

Add tests for:

- current trade popup/editor can render from authoritative `TradeState`
- human trade actions dispatch commands
- bot trade responses use the same command path

### Regression tests to keep green

- trade evaluator tests
- trade draft / offer tests as long as those helpers still exist
- bot simulation tests if touched
- popup layout tests

## Exact Files Likely Added In PR6

- `src/main/java/fi/monopoly/domain/session/TradeState.java`
- `src/main/java/fi/monopoly/domain/session/TradeStatus.java`
- `src/main/java/fi/monopoly/domain/session/TradeOfferState.java`
- `src/main/java/fi/monopoly/domain/session/TradeSelectionState.java`
- `src/main/java/fi/monopoly/domain/session/TradeHistoryEntry.java`
- `src/main/java/fi/monopoly/application/command/OpenTradeCommand.java`
- `src/main/java/fi/monopoly/application/command/EditTradeOfferCommand.java`
- `src/main/java/fi/monopoly/application/command/SubmitTradeOfferCommand.java`
- `src/main/java/fi/monopoly/application/command/AcceptTradeCommand.java`
- `src/main/java/fi/monopoly/application/command/DeclineTradeCommand.java`
- `src/main/java/fi/monopoly/application/command/CounterTradeCommand.java`
- `src/main/java/fi/monopoly/application/command/CancelTradeCommand.java`
- `src/main/java/fi/monopoly/application/session/TradeCommandHandler.java`
- `src/main/java/fi/monopoly/presentation/session/LegacyTradeGateway.java`
- `src/main/java/fi/monopoly/presentation/session/TradeViewAdapter.java`
- `src/main/java/fi/monopoly/domain/session/TradeEditPatch.java`

## Exact Files Likely Modified In PR6

- [TradeController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/trade/TradeController.java)
- [TradeUiBuilder.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/trade/TradeUiBuilder.java)
- [TradePopup.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/TradePopup.java)
- [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)
-
possibly [StrongTradePlanner.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/trade/StrongTradePlanner.java)
only to adapt execution path

## Review Checklist For PR6

PR6 is acceptable only if:

- trade authority lives in `TradeState` + commands
- current visual trade UI is only a renderer/editor
- humans and bots both use the command path
- accepted trades are applied atomically through one application-owned path
- `TradeController` is no longer the subsystem authority

## Main Risk In PR6

The biggest risk is trying to over-perfect trade UX and architecture in the same PR.

Mitigation:

- preserve current visual editor as much as possible
- move authority first
- defer optional UX redesigns

## Recommended Next Step After PR6

After PR6, the next planning/implementation focus should be:

- bot command unification sweep
- shrinking `Game` more aggressively into presentation shell

At that point the main stateful subsystems will already have command-driven authority.
