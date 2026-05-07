# PR2 Design Note: Property Purchase Slice

## Purpose

This document defines the first real behavior migration PR after the PR1 seam.

PR2 should migrate exactly one gameplay slice:

- landing on an unowned purchasable property
- creating an authoritative property-purchase pending decision
- resolving that decision through explicit commands
- transitioning either to:
    - property ownership
    - or auction start

This is the first PR where gameplay authority actually starts moving out of popup-owned flow.

## PR2 Goal

After PR2:

- property purchase is no longer owned by popup callbacks
- property purchase choice is represented as authoritative `PendingDecision`
- UI renders that decision through existing popup infrastructure
- decision resolution happens via:
    - `BuyPropertyCommand`
    - `DeclinePropertyCommand`
- decline opens authoritative auction state rather than a recursive popup-owned branch

Everything else remains legacy-driven for now.

## Explicit Non-Goals For PR2

PR2 must not:

- migrate rent flow
- migrate debt flow
- migrate jail decision flow
- migrate trade flow
- migrate bot strategy logic broadly
- redesign popup rendering architecture
- migrate all turn phases at once
- remove `InteractiveTurnEffectExecutor` entirely

If a change is not required to move property purchase authority, it is out of scope.

## Required Preconditions From PR1

PR2 assumes PR1 already added:

- `SessionState`
- `TurnState`
- `PendingDecision`
- `SessionApplicationService`
- `LegacySessionProjector`
- command/result/event/view-hint primitives

If PR1 does not yet expose a practical way to project current state and carry a command result, PR2 will be harder than
it should be.

## Exact New Types For PR2

### Commands

#### `fi.monopoly.application.command.BuyPropertyCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String decisionId`
- `String propertyId`

Notes:

- require `decisionId` explicitly
- this prevents stale or unrelated purchase actions from being applied

#### `fi.monopoly.application.command.DeclinePropertyCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String decisionId`
- `String propertyId`

### Domain decision payload

PR2 is the point where property purchase should stop using only freeform `summaryText`.

Add:

#### `fi.monopoly.domain.decision.PropertyPurchaseDecisionPayload`

Fields:

- `String propertyId`
- `String propertyDisplayName`
- `int price`

Then extend `PendingDecision` to include:

- `Object payload`

or better:

- `DecisionPayload payload`

Recommendation:

- if a small marker interface is easy, use `DecisionPayload`
- otherwise a temporary `Object payload` is acceptable for PR2 only

### Auction state

PR2 needs only minimal auction authority, not full auction migration.

Add:

#### `fi.monopoly.domain.session.AuctionState`

PR2 minimal fields:

- `String auctionId`
- `String propertyId`
- `String triggeringPlayerId`
- `String currentActorPlayerId`
- `String leadingPlayerId`
- `int currentBid`
- `int minimumNextBid`
- `Set<String> passedPlayerIds`
- `List<String> eligiblePlayerIds`
- `AuctionStatus status`

#### `fi.monopoly.domain.session.AuctionStatus`

PR2 minimal values:

- `ACTIVE`
- `WON_PENDING_RESOLUTION`

Notes:

- yes, this introduces auction types before the full auction slice
- that is acceptable because property decline must now land in authoritative auction state
- do not implement all auction commands in PR2
- only initialize state correctly on decline

## Existing Types To Extend In PR2

### `SessionState`

Add:

- `AuctionState auctionState`

PR2 should still not add debt/trade state.

### `DecisionType`

Must support:

- `PROPERTY_PURCHASE`

This likely already exists from PR1.

### `DecisionAction`

Must support:

- `BUY_PROPERTY`
- `DECLINE_PROPERTY`

## Authority Shift In PR2

This is the core design change.

### Before PR2

Current path:

- [PropertyTurnResolver.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/PropertyTurnResolver.java)
  emits `OfferToBuyPropertyEffect`
- [InteractiveTurnEffectExecutor.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/InteractiveTurnEffectExecutor.java)
  shows popup
- popup callback directly:
    - buys property
    - or starts auction

Authority currently lives in popup callback wiring.

### After PR2

Target path:

- landing on unowned property results in authoritative `PendingDecision`
- popup is only a rendering of that decision
- command handler resolves decision
- accepted command mutates authoritative state / or legacy-backed state adapter
- application returns updated `SessionState` + events + view hints

This is the first real decoupling move.

## Recommended Transitional Architecture

PR2 should still use a transitional adapter approach.

### New application component

#### `PropertyPurchaseCommandHandler`

Responsibilities:

- validate actor/phase/decision
- resolve `BuyPropertyCommand`
- resolve `DeclinePropertyCommand`
- produce new `SessionState`, `DomainEvent`, `ViewHint`

### New legacy bridge

#### `LegacyPropertyPurchaseGateway`

Purpose:

- wraps current imperative operations needed for property purchase

Likely responsibilities:

- locate property by id
- buy property through current property/player objects
- create initial auction state metadata on decline

Why:

- prevents command handler from directly depending on legacy UI classes
- makes future domain replacement easier

### New presentation bridge

#### `PendingDecisionPopupAdapter`

Purpose:

- render `PendingDecision` of type `PROPERTY_PURCHASE` via current `PropertyOfferPopup`

Responsibilities:

- map payload to current property image/card rendering
- wire popup buttons to:
    - `BuyPropertyCommand`
    - `DeclinePropertyCommand`

Important:

- popup adapter may dispatch commands
- it must not decide rule outcomes itself

## Required Current-Class Changes

### [PropertyTurnResolver.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/PropertyTurnResolver.java)

#### Current issue

- returns `OfferToBuyPropertyEffect` directly

#### PR2 target

Keep this class mostly intact if possible, but stop relying on `OfferToBuyPropertyEffect` as the long-term authority
mechanism.

Recommended transitional strategy:

- still detect “landed on unowned property”
- but route follow-up into creation of `PendingDecision` rather than direct popup-owned effect resolution

Two possible approaches:

##### Option A

Introduce a new turn effect:

- `OpenPendingDecisionEffect`

Pros:

- smaller local diff
- respects current turn-plan structure

Cons:

- another transitional adapter layer

##### Option B

Move property-purchase branching out of turn effects entirely for this slice

Pros:

- architecturally cleaner

Cons:

- larger PR2 scope

Recommendation:

- use Option A in PR2
- replace `OfferToBuyPropertyEffect` with a state-opening effect, not a popup-direct effect

### [InteractiveTurnEffectExecutor.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/InteractiveTurnEffectExecutor.java)

#### Current issue

- owns purchase accept/decline side effects directly

#### PR2 target

For property purchase only:

- stop calling `buyProperty(...)` from popup callback
- stop calling `PropertyAuctionResolver.resolve(...)` directly from popup callback
- instead:
    - open pending decision state
    - let popup adapter dispatch explicit commands

Keep legacy behavior for:

- message popups
- rent popup
- other branches still out of scope

### [PopupService.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PopupService.java)

#### PR2 target

Minimal extension only:

- allow property purchase popup rendering from `PendingDecisionViewState`

Do not:

- invent a second rule engine inside popup service

### [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)

#### PR2 target

Allowed:

- ask application service for current `SessionState`
- trigger pending-decision popup rendering when current state contains a property purchase decision
- dispatch buy/decline commands from popup adapter
- update UI after command result

Not allowed:

- implement property purchase rules in `Game`
- do new direct mutation logic there

## Exact State Mapping Rules

### Creating Property Purchase Pending Decision

When active player lands on an unowned purchasable property:

Create `PendingDecision`:

- `decisionId = generated stable id for this decision instance`
- `decisionType = PROPERTY_PURCHASE`
- `actorPlayerId = activePlayerId`
- `allowedActions = [BUY_PROPERTY, DECLINE_PROPERTY]`
- `payload = PropertyPurchaseDecisionPayload(propertyId, displayName, price)`

Also set:

- `turn.phase = WAITING_FOR_DECISION`

### Resolving `BuyPropertyCommand`

Validation:

- session is not game over
- `turn.phase == WAITING_FOR_DECISION`
- `pendingDecision != null`
- `pendingDecision.decisionType == PROPERTY_PURCHASE`
- `pendingDecision.decisionId == command.decisionId`
- `pendingDecision.actorPlayerId == command.actorPlayerId`
- payload property id matches command property id
- property still unowned
- actor still has enough cash

Mutation outcome:

- property owner becomes actor
- actor cash decreases by property price
- `pendingDecision = null`
- `auctionState = null`
- turn phase advances to next legacy-compatible state:
    - usually `WAITING_FOR_END_TURN`
    - or extra-roll state if required by existing turn plan

Events:

- `PropertyBought`

View hints:

- close purchase popup
- highlight property card
- refresh sidebar ownership

### Resolving `DeclinePropertyCommand`

Validation:

- same decision validation as buy command
- property still unowned

Mutation outcome:

- `pendingDecision = null`
- initialize `auctionState`
- `turn.phase = WAITING_FOR_AUCTION`

Events:

- `PropertyDeclined`
- `AuctionStarted`

View hints:

- close purchase popup
- open auction UI
- highlight property card

## Turn-Flow Handling In PR2

This is one of the most delicate points.

### Recommended approach

Do not try to rewrite `TurnEngine` fully in PR2.

Instead:

- keep current movement and follow-up planning alive
- splice the new property purchase decision opening into the point where purchase popup used to be triggered

Practical rule:

- PR2 may still rely on current turn engine and legacy player/property objects
- but the buy/decline decision itself must no longer be popup-owned

### Acceptable temporary compromise

The authoritative mutation in PR2 can still be “legacy-backed”:

- command handler validates against projected state
- then applies the change through current model objects
- then re-projects into new `SessionState`

This is acceptable for PR2 if it stays localized in the gateway/handler.

## Bot Behavior In PR2

Property purchase is also a bot-facing decision.

### Required behavior

Bots must resolve property purchase through the same explicit command path.

Meaning:

- bot chooses accept/decline
- application receives `BuyPropertyCommand` or `DeclinePropertyCommand`
- no direct `popupService.triggerPrimaryComputerAction()` path for this slice anymore

### Transitional allowance

Bot heuristics may still read legacy or projected view state in PR2.

That is acceptable.

The important part is:

- bot outcome goes through command handling

## Tests Required For PR2

### Application-level tests

Add tests for:

- `BuyPropertyCommand` accepted on valid property purchase decision
- rejected on wrong actor
- rejected on wrong decision id
- rejected on insufficient cash
- rejected if property became owned
- `DeclinePropertyCommand` opens `AuctionState`

### Projection / integration tests

Add tests for:

- property purchase pending decision is rendered as property offer popup
- accepting popup dispatches `BuyPropertyCommand`
- declining popup dispatches `DeclinePropertyCommand`

### Existing regressions to keep green

- property turn resolution tests
- popup layout tests
- turn control tests
- bot simulation tests if touched

## Exact Files Likely Added In PR2

- `src/main/java/fi/monopoly/application/command/BuyPropertyCommand.java`
- `src/main/java/fi/monopoly/application/command/DeclinePropertyCommand.java`
- `src/main/java/fi/monopoly/application/session/PropertyPurchaseCommandHandler.java`
- `src/main/java/fi/monopoly/domain/decision/PropertyPurchaseDecisionPayload.java`
- `src/main/java/fi/monopoly/domain/session/AuctionState.java`
- `src/main/java/fi/monopoly/domain/session/AuctionStatus.java`
- `src/main/java/fi/monopoly/presentation/session/PendingDecisionPopupAdapter.java`
- `src/main/java/fi/monopoly/presentation/session/LegacyPropertyPurchaseGateway.java`

Potentially:

- new turn effect type replacing `OfferToBuyPropertyEffect`

## Exact Files Likely Modified In PR2

- [PropertyTurnResolver.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/PropertyTurnResolver.java)
- [InteractiveTurnEffectExecutor.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/InteractiveTurnEffectExecutor.java)
- [PopupService.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PopupService.java)
- [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)
-
possibly [TurnEngine.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/TurnEngine.java)
only if necessary

## Review Checklist For PR2

PR2 is acceptable only if:

- property purchase no longer mutates game state from popup callbacks
- decline no longer directly starts auction from popup callback
- bots use the same buy/decline command path
- no rent/debt/trade behavior migrated accidentally
- `Game` did not become the new command handler
- the new command path is covered with application-level tests

## Main Risk In PR2

The biggest risk is accidental half-migration:

- popup no longer fully owns flow
- but command handler still secretly depends on popup timing or UI state

Mitigation:

- keep one clear rule:
    - command validation must use `SessionState`
    - mutation may temporarily go through legacy gateway
    - but popup state must not be the authority anymore

## Recommended Next Step After PR2

PR3 should be:

- rent + debt opening

That is the next place where popup-owned branching still strongly controls rule flow.
