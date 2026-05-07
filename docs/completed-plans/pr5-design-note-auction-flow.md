# PR5 Design Note: Full Auction Flow

## Purpose

This document defines the fifth migration PR after:

- PR1: session-state seam
- PR2: property purchase slice
- PR3: rent and debt opening
- PR4: debt remediation

PR5 should complete the auction subsystem by moving the full bid/pass/win loop behind authoritative state and explicit
commands.

PR2 already introduced the idea that property decline opens authoritative auction state. PR5 finishes that system.

## PR5 Goal

After PR5:

- auction turn order is authoritative state
- highest bid and leader are authoritative state
- pass state is authoritative state
- bid/pass actions are explicit commands
- auction winner resolution uses the explicit terminal state already chosen earlier
- `PropertyAuctionResolver` is no longer the authority for auction progression

This should be the second fully completed multi-step subsystem after debt.

## Explicit Non-Goals For PR5

PR5 must not:

- migrate trade flow
- redesign the visual auction popup from scratch
- redesign all bot bidding heuristics
- redesign all property-offer flows beyond what auction integration requires
- optimize networking payloads

If a change does not move auction authority into state + commands, it is out of scope.

## Required Preconditions

PR5 assumes:

- PR2 already opens `AuctionState` on property decline
- the system already supports explicit property purchase commands
- the system already has command/result/event primitives
- the auction terminal-state decision is already accepted:
    - auctions end in an explicit resolution state before returning to normal turn flow

## Exact Commands For PR5

### `PlaceAuctionBidCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String auctionId`
- `int amount`

Validation:

- auction exists
- actor is the current auction actor
- actor has not passed
- amount is at least `minimumNextBid`
- actor can afford the bid under current auction rules
- auction status is `ACTIVE`

### `PassAuctionCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String auctionId`

Validation:

- auction exists
- actor is the current auction actor
- actor has not already passed
- auction status is `ACTIVE`

### `FinishAuctionResolutionCommand`

Fields:

- `String sessionId`
- `String auctionId`

Validation:

- auction exists
- auction status is `WON_PENDING_RESOLUTION`

Notes:

- this is expected to be an internal/system-issued command first
- later, if desired, it can also be driven by explicit acknowledgement UX

## Existing Types To Extend In PR5

### `AuctionState`

PR5 should make this fully useful:

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
- `int winningBid`
- `String winningPlayerId`

### `AuctionStatus`

PR5 values:

- `ACTIVE`
- `WON_PENDING_RESOLUTION`

These two should be enough for the first full auction state machine.

### `DomainEvent`

Auction flow will need at least:

- `AuctionStarted`
- `AuctionBidPlaced`
- `AuctionPassed`
- `AuctionWon`
- `AuctionResolved`

## Current Legacy Flow To Replace

Current flow lives mainly in:

- [PropertyAuctionResolver.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/PropertyAuctionResolver.java)
- [PopupService.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PopupService.java)
- [PropertyAuctionPopup.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PropertyAuctionPopup.java)

Current authority problems:

- active bidder iteration happens inside resolver recursion
- pass handling lives in popup callback flow
- computer bidding and human bidding are both routed through popup-oriented orchestration
- auction completion applies property purchase directly in resolver

PR5 should move all of that authority out of popup recursion.

## Recommended Transitional Architecture

### New application component

#### `AuctionCommandHandler`

Responsibilities:

- validate bid/pass commands
- update authoritative `AuctionState`
- detect winner and terminal state transition
- emit auction domain events
- prepare view hints

### New legacy bridge

#### `LegacyAuctionGateway`

Responsibilities:

- apply winning property transfer using current player/property objects
- validate affordability against current runtime objects if needed temporarily
- expose ordered eligible bidders from current player order

Why:

- keeps PR5 from rewriting low-level ownership transfer immediately
- localizes dependence on legacy player/property objects

### Presentation bridge

#### `AuctionViewAdapter`

Responsibilities:

- render `AuctionState` via current `PropertyAuctionPopup`
- dispatch `PlaceAuctionBidCommand` / `PassAuctionCommand`
- react to `WON_PENDING_RESOLUTION` state appropriately

Important:

- UI should reflect authoritative auction actor, leader, bid, and pass state
- popup must not own auction progression anymore

## Exact State Transition Rules

### Auction start

PR2 already introduced this concept. In PR5 it becomes fully relied upon.

On decline or bank-driven auction start:

- initialize eligible players
- initialize current actor
- initialize current bid = 0
- initialize minimum next bid = opening bid
- `status = ACTIVE`
- `leadingPlayerId = null`
- `winningPlayerId = null`

### Bid command accepted

Mutation:

- `currentBid = amount`
- `leadingPlayerId = actorPlayerId`
- `minimumNextBid = amount + increment`
- advance `currentActorPlayerId` to next eligible non-passed player

Events:

- `AuctionBidPlaced`

View hints:

- refresh highest bid UI
- refresh leader token/name

### Pass command accepted

Mutation:

- add actor to `passedPlayerIds`
- if only one non-passed eligible player remains and there is a leader:
    - `status = WON_PENDING_RESOLUTION`
    - `winningPlayerId = leadingPlayerId`
    - `winningBid = currentBid`
- else:
    - advance `currentActorPlayerId` to next eligible non-passed player

Events:

- `AuctionPassed`
- optionally `AuctionWon` if the pass determines the winner

View hints:

- refresh pass state
- refresh next actor

### Special case: no bids at all

If all players pass while `leadingPlayerId == null`:

Mutation:

- clear `auctionState`
- return to normal turn flow

Events:

- optionally `AuctionEndedWithoutWinner`

View hints:

- close auction popup

### Finish resolution command accepted

Mutation:

- transfer property to winning player for `winningBid`
- clear `auctionState`
- return turn flow to:
    - usually `WAITING_FOR_END_TURN`

Events:

- `AuctionResolved`

View hints:

- close auction UI
- highlight bought property

## Human And Bot Behavior In PR5

### Human

Human must interact through:

- `PlaceAuctionBidCommand`
- `PassAuctionCommand`

No direct auction mutation in popup callbacks.

### Bot

Bot must also act through:

- `PlaceAuctionBidCommand`
- `PassAuctionCommand`

Temporary allowance:

- current bid-selection heuristics can be reused
- but execution must go through the new command path

This is important because auction is one of the clearest multi-actor test cases for the new command boundary.

## `PropertyAuctionResolver` Migration Rule

PR5 should
make [PropertyAuctionResolver.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/PropertyAuctionResolver.java)
stop owning the auction loop.

After PR5 it may still temporarily contribute:

- bid heuristics
- bidder ordering helpers
- metrics recording helpers

But it should no longer:

- recursively drive the auction
- own bid/pass progression
- directly finalize property transfer from popup recursion

Strong recommendation:

- either shrink it heavily
- or split reusable bits into narrower helpers and stop calling it “resolver” if it no longer resolves the loop

## Current-Class Change Guidance

### [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)

Allowed:

- render auction UI based on authoritative state
- dispatch bid/pass commands
- trigger internal finish-resolution command after animation timing if needed

Not allowed:

- own bidder rotation or winner logic

### [PopupService.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PopupService.java)

Allowed:

- act as renderer/adapter for auction state

Not allowed:

- own auction branching logic

### [PropertyAuctionPopup.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PropertyAuctionPopup.java)

Allowed:

- visual display of:
    - property
    - current leader
    - highest bid
    - action labels

Not allowed:

- rule progression beyond dispatching commands

## Tests Required For PR5

### Application-level tests

Add tests for:

- bid command rejected for wrong actor
- bid command rejected below minimum bid
- pass command advances actor correctly
- pass command can produce terminal winner state
- no-bid auction closes without winner
- finish resolution command transfers property and clears auction

### Integration tests

Add tests for:

- auction popup renders from authoritative `AuctionState`
- human popup buttons dispatch bid/pass commands
- bot auction actions use command path

### Regression tests to keep green

- auction-related gameplay tests
- property purchase tests
- bot simulation tests

## Exact Files Likely Added In PR5

- `src/main/java/fi/monopoly/application/command/PlaceAuctionBidCommand.java`
- `src/main/java/fi/monopoly/application/command/PassAuctionCommand.java`
- `src/main/java/fi/monopoly/application/command/FinishAuctionResolutionCommand.java`
- `src/main/java/fi/monopoly/application/session/AuctionCommandHandler.java`
- `src/main/java/fi/monopoly/presentation/session/LegacyAuctionGateway.java`
- `src/main/java/fi/monopoly/presentation/session/AuctionViewAdapter.java`

## Exact Files Likely Modified In PR5

- [PropertyAuctionResolver.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/turn/PropertyAuctionResolver.java)
- [PopupService.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PopupService.java)
- [PropertyAuctionPopup.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PropertyAuctionPopup.java)
- [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)

## Review Checklist For PR5

PR5 is acceptable only if:

- auction loop no longer lives in popup recursion
- humans and bots both bid/pass through commands
- auction leader/highest bid/current actor are authoritative state
- explicit terminal resolution state is actually used
- `PropertyAuctionResolver` is no longer the subsystem authority

## Main Risk In PR5

The main risk is trying to redesign both auction rules and auction UX in the same PR.

Mitigation:

- keep current popup visuals mostly intact
- move only authority and state progression
- defer major UX redesigns

## Recommended Next Step After PR5

PR6 should be:

- trade flow migration

That is the next large stateful subsystem still heavily owned by UI/controller flow.
