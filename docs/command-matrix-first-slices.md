# Command Matrix For First Migration Slices

## Purpose

This document turns the architectural spec into an implementation planning artifact.

It answers, per command:

- who may send it
- in which authoritative phase it is valid
- what state it mutates
- which events it emits
- what view/presentation hints it may produce

The goal is to make the first migration slices concrete enough to estimate and implement without broad guesswork.

## Scope

This matrix covers the first recommended migration slices:

1. property purchase / decline
2. rent flow
3. debt resolution
4. auction flow
5. autoplay control

Trade is intentionally left for a later matrix because it deserves its own negotiation-focused pass.

## Conventions

### Actor Rules

- `active player` means `SessionState.turn.activePlayerId`
- `decision actor` means `PendingDecision.actorPlayerId`
- `auction actor` means `AuctionState.currentActorPlayerId`
- `debtor` means `DebtState.debtorPlayerId`

### Response Types

Each command attempt can result in:

- `ACCEPT`
- `REJECT`

This document assumes no silent ignore behavior for gameplay commands.

### View Hints

View hints are optional outputs from the application layer. They are not authoritative state.

Examples:

- animate token movement
- focus property card
- open decision popup
- open debt panel
- scroll recent messages

## Slice 1: Property Purchase / Decline

### Precondition State

This slice begins after movement has already been resolved and the active player has landed on an unowned purchasable
property.

Expected authoritative outcome before user input:

- `turn.phase = WAITING_FOR_DECISION`
- `pendingDecision.decisionType = PROPERTY_PURCHASE`
- `pendingDecision.actorPlayerId = activePlayerId`

### BuyPropertyCommand

#### Allowed actor

- pending decision actor only

#### Allowed phase

- `WAITING_FOR_DECISION`

#### Additional validation

- `pendingDecision.decisionType == PROPERTY_PURCHASE`
- property is still unowned
- actor has enough cash
- actor is still the active player

#### Mutations

- subtract purchase price from actor cash
- assign property owner to actor
- clear `pendingDecision`
- if doubles/extra-roll logic does not apply:
    - set `turn.phase = WAITING_FOR_END_TURN`
- else:
    - set `turn.phase = WAITING_FOR_ROLL`
    - set appropriate extra-roll flags

#### Events

- `PropertyBought`

#### View hints

- highlight bought property
- update sidebar ownership
- close decision UI
- add recent message from event projection

#### Rejection cases

- insufficient cash
- wrong actor
- wrong pending decision type
- stale property ownership

### DeclinePropertyCommand

#### Allowed actor

- pending decision actor only

#### Allowed phase

- `WAITING_FOR_DECISION`

#### Additional validation

- `pendingDecision.decisionType == PROPERTY_PURCHASE`
- property is still unowned

#### Mutations

- clear `pendingDecision`
- create `auctionState`
- set `auctionState.propertyId`
- set eligible bidders
- initialize `currentBid`, `minimumNextBid`, `leadingPlayerId`
- set `turn.phase = WAITING_FOR_AUCTION`

#### Events

- `PropertyDeclined`
- `AuctionStarted`

#### View hints

- close property purchase UI
- open auction UI
- focus property card

#### Rejection cases

- stale ownership
- wrong actor
- wrong phase

### Property Purchase Slice Notes

Implementation recommendation:

- property purchase should be the first slice because it introduces the basic pattern:
    - authoritative pending decision
    - command response
    - event emission
    - view hint generation

## Slice 2: Rent Flow

### Precondition State

This slice begins after landing on a property owned by another player where rent is due.

### Automatic Rent Resolution Branch

If the active player can pay immediately:

#### Mutations

- subtract rent from debtor
- add rent to creditor
- set next turn phase:
    - usually `WAITING_FOR_END_TURN`
    - or another phase if special flow says otherwise

#### Events

- `RentCharged`

#### View hints

- show rent summary
- animate money transfer if desired

No player command is required in this branch.

### Rent Causes Debt Branch

If cash is insufficient:

#### Mutations

- create `activeDebt`
- set `turn.phase = RESOLVING_DEBT`
- optional:
    - create `pendingDecision` only if the UI needs an immediate actor selection prompt

#### Events

- `RentCharged`
- `DebtOpened`

#### View hints

- open debt resolution panel
- focus debtor assets

### Rent Slice Notes

Implementation recommendation:

- keep rent calculation pure and separate from UI strings
- popup wording should become a projection from:
    - `RentCharged`
    - property metadata
    - development level

## Slice 3: Debt Resolution

### DebtState Assumption

While `activeDebt != null`:

- `turn.phase = RESOLVING_DEBT`
- only a restricted command set is legal

### PayDebtCommand

#### Allowed actor

- debtor only

#### Allowed phase

- `RESOLVING_DEBT`

#### Additional validation

- actor equals `activeDebt.debtorPlayerId`
- actor has enough liquid cash to pay `amountRemaining`

#### Mutations

- transfer remaining amount to creditor
- clear `activeDebt`
- clear any related debt decision state
- resume turn flow:
    - typically `WAITING_FOR_END_TURN`

#### Events

- `DebtResolved`

#### View hints

- close debt UI
- refresh cash/ownership panels

### ResolveDebtByMortgageCommand

#### Allowed actor

- debtor only

#### Allowed phase

- `RESOLVING_DEBT`

#### Additional validation

- property belongs to debtor
- property is mortgageable under rules
- property is not already mortgaged

#### Mutations

- mark property mortgaged
- add mortgage proceeds to debtor cash
- if debt now payable:
    - debt remains open until explicit `PayDebtCommand`, unless you choose auto-pay behavior later

#### Events

- `PropertyMortgaged`
- optionally `DebtProgressChanged`

#### View hints

- refresh property card state
- keep debt UI open

### ResolveDebtBySellingBuildingCommand

#### Allowed actor

- debtor only

#### Allowed phase

- `RESOLVING_DEBT`

#### Additional validation

- property belongs to debtor
- property is a street
- building sale is legal under even-building rules

#### Mutations

- reduce house/hotel count as requested
- add sale proceeds to debtor cash

#### Events

- `BuildingSold`
- optionally `DebtProgressChanged`

#### View hints

- refresh property development visuals
- keep debt UI open

### DeclareBankruptcyCommand

#### Allowed actor

- debtor only

#### Allowed phase

- `RESOLVING_DEBT`

#### Additional validation

- active debt exists
- actor equals debtor

#### Mutations

- mark debtor eliminated/bankrupt
- transfer or auction assets according to creditor type and rules
- clear `activeDebt`
- if one player remains:
    - set `winnerPlayerId`
    - set `status = GAME_OVER`
    - set `turn.phase = GAME_OVER`
- else:
    - advance session to next legal turn flow

#### Events

- `BankruptcyDeclared`
- optionally:
    - `AuctionStarted`
    - `WinnerDeclared`

#### View hints

- open winner/game-over UI if needed
- animate asset transfer if desired

### Debt Slice Notes

This has now been decided:

- debt should not auto-pay the moment cash becomes sufficient
- debt remains open until the debtor explicitly issues `PayDebtCommand`

Reason:

- matches the current gameplay better
- keeps debt flow visible and deliberate
- is easier to reason about for networking and save/resume

## Slice 4: Auction Flow

### Precondition State

Auction is active when:

- `auctionState != null`
- `turn.phase = WAITING_FOR_AUCTION`

### PlaceAuctionBidCommand

#### Allowed actor

- `auctionState.currentActorPlayerId` only

#### Allowed phase

- `WAITING_FOR_AUCTION`

#### Additional validation

- actor is eligible and not passed
- bid is at least `minimumNextBid`
- actor can afford the bid under auction cash rules

#### Mutations

- set `currentBid`
- set `leadingPlayerId`
- update `minimumNextBid`
- advance `currentActorPlayerId` to next eligible non-passed bidder

#### Events

- `AuctionBidPlaced`

#### View hints

- refresh leader token/name
- refresh highest bid chip

### PassAuctionCommand

#### Allowed actor

- `auctionState.currentActorPlayerId` only

#### Allowed phase

- `WAITING_FOR_AUCTION`

#### Additional validation

- actor is still eligible

#### Mutations

- add actor to `passedPlayerIds`
- if only one non-passed bidder remains:
    - resolve winner immediately
- else:
    - advance `currentActorPlayerId`

#### Events

- `AuctionPassed`
- optionally `AuctionWon` if pass resolves the auction

#### View hints

- refresh eligible/pass state
- close auction UI if won

### Auction Resolution

When auction ends:

#### Mutations

- move auction into an explicit terminal resolution state
- set winner and winning bid as authoritative auction outcome
- do not immediately collapse auction back to null in the same conceptual step

#### Events

- `AuctionWon`

#### View hints

- show winner emphasis
- allow winner animation timing before returning to normal turn flow

### FinishAuctionResolutionCommand

This command exists because auction end-state should be explicit.

#### Allowed actor

- system/internal application actor at first
- later this could also be driven by an acknowledgement-style decision if desired

#### Allowed phase

- `WAITING_FOR_AUCTION`
- with `auctionState.status = WON_PENDING_RESOLUTION`

#### Mutations

- transfer property to winner
- subtract winning bid from winner cash
- clear `auctionState`
- return turn flow to:
    - usually `WAITING_FOR_END_TURN`

#### Events

- `AuctionResolved`

#### View hints

- close auction UI
- highlight bought property

### Auction Slice Notes

Implementation recommendation:

- keep full bidder order and pass state authoritative
- avoid recursive popup chaining in the application layer

## Slice 5: Autoplay Control

### ToggleAutoplayCommand

#### Allowed actor

- controlling human for the seat being toggled

For the first local implementation this can be simplified to:

- local human may toggle autoplay for their own seat only

#### Allowed phase

- any non-terminal phase

Suggested allowed phases:

- `WAITING_FOR_ROLL`
- `WAITING_FOR_DECISION`
- `RESOLVING_DEBT`
- `WAITING_FOR_END_TURN`
- `WAITING_FOR_AUCTION`
- `WAITING_FOR_TRADE_RESPONSE`

#### Additional validation

- target seat is a human-owned seat
- actor is allowed to control that seat

#### Mutations

- flip `SeatState.controlMode`
- no change to:
    - `seatKind`
    - `playerId`
    - ownership of pending decisions

#### Events

- `AutoplayEnabled`
- or `AutoplayDisabled`

#### View hints

- update seat status label
- show brief control-mode change indicator

### Autoplay During Pending Decision

This was explicitly chosen in the design discussion.

Rule:

- autoplay may be interrupted immediately even if a pending decision already exists
- no authoritative decision is recreated
- only the controlling entity changes

Practical consequence:

- the UI should simply re-render the same pending decision as manually answerable
- when autoplay is enabled, the bot may answer pending decisions immediately with no grace delay

## Shared Command Validation Matrix

### Allowed actor categories

- `ACTIVE_PLAYER_ONLY`
- `DECISION_ACTOR_ONLY`
- `DEBTOR_ONLY`
- `AUCTION_ACTOR_ONLY`
- `SEAT_CONTROLLER_ONLY`

These categories should become reusable validator helpers in the application layer.

### Shared rejection reasons

Suggested canonical rejection codes:

- `WRONG_PHASE`
- `WRONG_ACTOR`
- `STALE_VERSION`
- `NO_PENDING_DECISION`
- `WRONG_DECISION_TYPE`
- `INSUFFICIENT_FUNDS`
- `ILLEGAL_PROPERTY_STATE`
- `ILLEGAL_BUILDING_ACTION`
- `AUCTION_NOT_ACTIVE`
- `DEBT_NOT_ACTIVE`
- `SESSION_NOT_ACTIVE`

Recommendation:

- define these early so UI and logs stay consistent

## Suggested Implementation Tasks Derived From This Matrix

### Task 1

Introduce application command envelope and result type:

- `CommandResult`
- `CommandRejection`
- `DomainEvent`
- `ViewHint`

### Task 2

Introduce authoritative state objects for:

- `SessionState`
- `TurnState`
- `PendingDecision`

without yet migrating all gameplay logic

### Task 3

Migrate property purchase / decline vertical slice to:

- pending decision
- `BuyPropertyCommand`
- `DeclinePropertyCommand`

### Task 4

Migrate rent + debt opening to authoritative `DebtState`

### Task 5

Migrate debt remediation commands:

- mortgage
- sell building
- pay debt
- bankruptcy

### Task 6

Migrate auction loop to authoritative `AuctionState`

### Task 7

Introduce `ToggleAutoplayCommand` and seat `controlMode`

## Open Questions For Next Iteration

1. Should auction terminal resolution be finished automatically by the application after animation timing, or should it
   wait for an explicit acknowledgement-style decision in some cases?
2. During debt flow, should the UI stay as a continuously editable asset panel until `PayDebtCommand`, or should some
   debt actions still open narrower sub-decisions?
3. For generic low-value decisions later, what criteria should decide whether they use `ChooseDecisionOptionCommand`
   versus explicit command types?
