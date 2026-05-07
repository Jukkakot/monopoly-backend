# Session State And Command Spec

## Purpose

This document defines the first concrete contract for separating game rules from the Processing UI.

It is intentionally implementation-oriented. The goal is to make the first migration tasks plannable without yet
committing to final class names or module boundaries.

This spec is for:

- authoritative in-process application state first
- backend-safe design later
- full snapshot synchronization first
- Processing client as the first presentation implementation

This spec is not yet the transport schema. It is the logical contract that transport and UI should later follow.

## Design Goals

The contract should satisfy these constraints:

- game rules do not depend on Processing, ControlP5, popup widgets, or rendering classes
- UI never decides game outcomes directly
- bots and humans use the same command boundary
- pending decisions are authoritative state, not transient widget state
- the model supports local play, bot-only play, autoplay delegation, and later remote multiplayer
- full snapshot sync is easy before diff sync exists
- save and resume can include mid-decision states

## Non-Goals

This spec does not yet define:

- WebSocket payload formats
- persistence table structure
- replay/event sourcing
- final package layout
- exact Java record/class syntax

## Core Model

### SessionState

`SessionState` is the single authoritative state object for one running game session.

Proposed shape:

- `sessionId`
- `version`
- `status`
- `rulesetVersion`
- `board`
- `players`
- `seats`
- `turn`
- `pendingDecision`
- `activeDebt`
- `activeTrade`
- `auctionState`
- `decks`
- `eliminatedPlayerIds`
- `winnerPlayerId`
- `recentEvents`
- `createdAt`
- `updatedAt`

### SessionStatus

Suggested values:

- `LOBBY`
- `IN_PROGRESS`
- `PAUSED`
- `GAME_OVER`
- `ABANDONED`

Notes:

- `PAUSED` is session-level and should not be confused with a popup or pending decision.
- `GAME_OVER` means no further gameplay commands are accepted except optional post-game UX commands.

### Session Versioning

`version` must increment after every accepted authoritative command.

Why:

- makes full snapshot sync simple
- gives optimistic ordering later for network clients
- helps save/resume and debugging

## Seat Model

### Why Seats Matter

The authoritative ownership unit should be `seat`, not `client`.

Reason:

- local play against bots
- later remote human seats
- optional future multi-seat local control
- temporary autoplay for a human-owned seat

### SeatState

Proposed shape:

- `seatId`
- `seatIndex`
- `playerId`
- `seatKind`
- `controlMode`
- `controllerProfileId`
- `botProfile`
- `ownerIdentity`
- `connected`
- `displayName`
- `tokenColorHex`
- `tokenColorKey`

### SeatKind

Suggested values:

- `HUMAN`
- `BOT`

### ControlMode

Suggested values:

- `MANUAL`
- `AUTOPLAY`

Important distinction:

- `seatKind` describes what kind of seat this is
- `controlMode` describes who is currently allowed to act for the seat

Example:

- a human seat temporarily delegated to autoplay remains:
    - `seatKind = HUMAN`
    - `controlMode = AUTOPLAY`

This is the model that best matches the product intent.

### Owner Identity

Keep this abstract for now.

Proposed shape:

- `ownerType`
- `ownerId`

Possible future values:

- local client identity
- remote account id
- private local controller id

For local single-player MVP, this can be minimal.

## Player Model

### PlayerState

Proposed shape:

- `playerId`
- `seatId`
- `name`
- `tokenKey`
- `cash`
- `position`
- `inJail`
- `jailRoundsRemaining`
- `getOutOfJailCards`
- `bankrupt`
- `eliminated`
- `ownedPropertyIds`
- `mortgagedPropertyIds`
- `netWorthEstimate`

Notes:

- `ownedPropertyIds` should be the authoritative ownership index, not something inferred from UI components.
- `netWorthEstimate` is optional derived data for view convenience. It is not required for rules.

## Board And Property Model

### BoardState

Keep the board mostly static and separate dynamic ownership/development from static spot definitions.

Proposed shape:

- `spots`
- `propertyStates`

### SpotState

Proposed shape:

- `spotId`
- `spotType`
- `index`

Board topology should be stable and mostly static.

### PropertyState

Proposed shape:

- `propertyId`
- `spotId`
- `propertyType`
- `ownerPlayerId`
- `mortgaged`
- `houseCount`
- `hotelCount`

Rules:

- ownership and development must be authoritative here, not split across UI caches
- railroads and utilities should use the same base structure even if they ignore house/hotel fields

## Turn Model

### TurnState

Proposed shape:

- `turnNumber`
- `activePlayerId`
- `phase`
- `rollCountThisTurn`
- `doublesCountThisTurn`
- `dice`
- `mustEndTurn`
- `canRoll`
- `canEndTurn`
- `turnFlags`

### TurnPhase

Suggested values:

- `WAITING_FOR_ROLL`
- `ANIMATING_MOVEMENT`
- `RESOLVING_LANDING`
- `WAITING_FOR_DECISION`
- `RESOLVING_DEBT`
- `WAITING_FOR_END_TURN`
- `WAITING_FOR_AUCTION`
- `WAITING_FOR_TRADE_RESPONSE`
- `GAME_OVER`

Notes:

- exact naming can still change
- keep phases authoritative enough that UI does not infer action availability from heuristics

### DiceState

Proposed shape:

- `first`
- `second`
- `total`
- `isDouble`
- `rolledThisTurn`

Do not expose UI-specific dice animation state here. Animation belongs in presentation.

## Decision Model

### Why PendingDecision Exists

Current popup flow is UI-owned. That is the wrong abstraction for save/load and networking.

The authoritative model must instead say:

- player X must now choose among options Y
- here is the payload/context for the choice

The client then decides whether this is rendered as:

- popup
- panel
- inline buttons
- mobile sheet later

### PendingDecision

Proposed shape:

- `decisionId`
- `decisionType`
- `actorPlayerId`
- `source`
- `payload`
- `allowedActions`
- `createdAtVersion`
- `expiresWhenStateChanges`

### DecisionType

Suggested initial values:

- `PROPERTY_PURCHASE`
- `JAIL_CHOICE`
- `AUCTION_TURN`
- `TRADE_RESPONSE`
- `TRADE_EDIT`
- `DEBT_ACTION`
- `GENERIC_CONFIRM`
- `GENERIC_INFO`

### DecisionSource

Useful for traceability:

- `SPOT_RESOLUTION`
- `TRADE_NEGOTIATION`
- `DEBT_FLOW`
- `AUCTION_FLOW`
- `CARD_EFFECT`
- `DEBUG_ACTION`

### AllowedActions

Model allowed actions explicitly rather than relying on button labels.

For example:

- property purchase:
    - `BUY_PROPERTY`
    - `DECLINE_PROPERTY`
- jail:
    - `PAY_JAIL_FINE`
    - `USE_JAIL_CARD`
    - `STAY_IN_JAIL`
- auction:
    - `PLACE_AUCTION_BID`
    - `PASS_AUCTION`

### Decision Payload

Payload should be typed by `decisionType`.

Do not use one giant string blob.

Examples:

- `PropertyPurchaseDecisionPayload`
- `JailChoiceDecisionPayload`
- `AuctionTurnDecisionPayload`
- `TradeResponseDecisionPayload`
- `DebtDecisionPayload`

## Flow-Specific State

### DebtState

Debt is too stateful to model as a single popup.

Proposed shape:

- `debtId`
- `debtorPlayerId`
- `creditorType`
- `creditorPlayerId`
- `amountRemaining`
- `reason`
- `allowedRemediationActions`
- `auctionOnFailure`
- `startedAtVersion`

### AuctionState

Auction should be explicit state, not a recursive popup loop.

Proposed shape:

- `auctionId`
- `propertyId`
- `triggeringPlayerId`
- `currentBid`
- `leadingPlayerId`
- `eligiblePlayerIds`
- `passedPlayerIds`
- `currentActorPlayerId`
- `minimumNextBid`
- `status`

### TradeState

Trade is a multi-step negotiation and must be authoritative if it will later survive save/load/networking.

Proposed shape:

- `tradeId`
- `initiatorPlayerId`
- `recipientPlayerId`
- `status`
- `currentOffer`
- `lastOfferByPlayerId`
- `history`
- `activeEditorPlayerId`
- `decisionRequiredFromPlayerId`

### TradeOfferState

Proposed shape:

- `offeredPropertyIds`
- `requestedPropertyIds`
- `offeredCash`
- `requestedCash`
- `offeredJailCards`
- `requestedJailCards`

This should be neutral and symmetric. Avoid storing one side as special UI-only data.

## Command Model

### Command Envelope

Every command should carry:

- `commandId`
- `sessionId`
- `actorPlayerId`
- `expectedVersion`
- `payload`

Why:

- idempotency later
- replay/debug value
- straightforward network transport later

### Command Validity Rule

A command may be:

- `ACCEPTED`
- `REJECTED`
- `IGNORED`

`IGNORED` should be rare and explicit.

Default preference:

- invalid actor or invalid phase should be `REJECTED`
- duplicate replay with already applied `commandId` can later become `IGNORED`

### Initial Command Set

#### Session / control commands

- `StartSessionCommand`
- `PauseSessionCommand`
- `ResumeSessionCommand`
- `ToggleAutoplayCommand`
- `ChangeLanguageCommand`

Note:

- `ChangeLanguageCommand` is presentation/application scoped, not a game-rule command. It may eventually live outside
  authoritative session commands. Keep that open.

#### Turn commands

- `RollDiceCommand`
- `EndTurnCommand`

#### Pending decision commands

- `ChooseDecisionOptionCommand`

This is likely the most useful generic command early in migration.

It should contain:

- `decisionId`
- `decisionAction`
- typed payload

#### Property / development commands

- `BuyPropertyCommand`
- `DeclinePropertyCommand`
- `BuyBuildingRoundCommand`
- `SellBuildingCommand`
- `SellBuildingRoundsAcrossSetCommand`
- `MortgagePropertyCommand`
- `UnmortgagePropertyCommand`

#### Debt commands

- `PayDebtCommand`
- `ResolveDebtByMortgageCommand`
- `ResolveDebtBySellingBuildingCommand`
- `DeclareBankruptcyCommand`

#### Auction commands

- `PlaceAuctionBidCommand`
- `PassAuctionCommand`

#### Trade commands

- `OpenTradeCommand`
- `EditTradeOfferCommand`
- `SubmitTradeOfferCommand`
- `AcceptTradeCommand`
- `DeclineTradeCommand`
- `CounterTradeCommand`
- `CancelTradeCommand`

### Generic Versus Specific Commands

Migration recommendation:

- allow generic `ChooseDecisionOptionCommand` early if it reduces churn
- but move high-value gameplay paths to specific commands over time

Practical rule:

- if the action is generic UI confirmation, generic is fine
- if the action affects core rules materially and will likely be remote later, prefer specific commands

## Domain Event Model

### Purpose

Domain events are for:

- audit/debugging
- client animation hints
- concise recent-message generation
- later event stream support

Events are not authoritative state by themselves. State remains authoritative.

### DomainEvent Envelope

Proposed shape:

- `eventId`
- `sessionId`
- `version`
- `eventType`
- `actorPlayerId`
- `payload`

### Initial Event Set

- `DiceRolled`
- `PlayerMoved`
- `PlayerPassedGo`
- `PropertyPurchaseOffered`
- `PropertyBought`
- `PropertyDeclined`
- `RentCharged`
- `DebtOpened`
- `DebtResolved`
- `DebtFailed`
- `BankruptcyDeclared`
- `AuctionStarted`
- `AuctionBidPlaced`
- `AuctionPassed`
- `AuctionWon`
- `TradeOpened`
- `TradeOfferUpdated`
- `TradeOfferSubmitted`
- `TradeAccepted`
- `TradeDeclined`
- `TradeCountered`
- `TurnEnded`
- `TurnStarted`
- `AutoplayEnabled`
- `AutoplayDisabled`
- `WinnerDeclared`

### Event Storage

For now:

- keep only a bounded recent event list in-memory for UI/logging convenience
- persistence can still store command log + snapshots

Do not accidentally drift into full event sourcing.

## Application Boundary

### SessionApplicationService

Target high-level interface:

- load current session state
- accept command
- validate actor and phase
- apply domain transition
- return updated snapshot + produced events + presentation hints

Proposed response shape:

- `newState`
- `events`
- `rejections`
- `viewHints`

### View Hints

These are not domain rules. They are optional presentation suggestions.

Examples:

- animate player movement from X to Y
- highlight property card
- show trade panel
- scroll recent messages

This keeps animation/UI concerns out of state transitions while still giving the client useful cues.

## View State Direction

The Processing UI should eventually render from projection/view-state objects, not directly from domain state.

Suggested projections:

- `SessionViewState`
- `BoardViewState`
- `PlayerSidebarViewState`
- `PendingDecisionViewState`
- `TradeViewState`
- `AuctionViewState`
- `RecentMessagesViewState`
- `AvailableActionsViewState`

This projection step can initially be in-process and synchronous.

## Ownership And Authority Rules

### Rule 1

Only the application/domain boundary mutates authoritative game state.

### Rule 2

UI may cache layout, animation, and rendering data, but never owns rule progress.

### Rule 3

Bots do not call UI methods or shortcut around commands.

### Rule 4

When a decision is pending, only the designated actor player may issue decision-resolving commands.

Exception:

- local debug/admin commands can bypass this, but should be explicitly marked as debug-only

### Rule 5

Autoplay never changes seat ownership. It changes only control delegation for the seat.

## Example Flows

### Example 1: Property Purchase

#### Initial state

- active player lands on unowned property
- `turn.phase = RESOLVING_LANDING`
- `pendingDecision = null`

#### Domain/application result

- create `PendingDecision`
    - `decisionType = PROPERTY_PURCHASE`
    - `actorPlayerId = landing player`
    - `payload = propertyId, price`
    - `allowedActions = BUY_PROPERTY, DECLINE_PROPERTY`
- set `turn.phase = WAITING_FOR_DECISION`
- emit `PropertyPurchaseOffered`

#### If accepted

- command: `BuyPropertyCommand`
- validate actor, decision id, cash, ownership
- mutate ownership/cash
- clear `pendingDecision`
- emit `PropertyBought`
- move to:
    - `WAITING_FOR_EXTRA_ROLL` if rules allow
    - else `WAITING_FOR_END_TURN`

#### If declined

- command: `DeclinePropertyCommand`
- clear `pendingDecision`
- create `AuctionState`
- set phase `WAITING_FOR_AUCTION`
- emit `PropertyDeclined`
- emit `AuctionStarted`

### Example 2: Rent Payment

#### Initial state

- player lands on owned property

#### Domain/application result

- calculate rent
- if cash is sufficient:
    - transfer funds
    - emit `RentCharged`
    - continue turn flow
- if cash is insufficient:
    - create `DebtState`
    - optionally create `PendingDecision` if a user choice is required immediately
    - set phase `RESOLVING_DEBT`
    - emit `DebtOpened`

Important:

the popup text shown to users is a presentation of this state, not the state itself.

### Example 3: Debt Resolution

#### While debt remains

Allowed commands may include:

- mortgage
- sell building
- declare bankruptcy
- pay debt if funds become sufficient

State remains authoritative in `activeDebt`.

When `amountRemaining == 0`:

- clear `activeDebt`
- emit `DebtResolved`
- return to turn flow

If bankruptcy is declared:

- transfer assets per rules
- mark debtor eliminated
- emit `BankruptcyDeclared`
- if one player remains, set `winnerPlayerId` and `GAME_OVER`

### Example 4: Auction

#### Start

- `AuctionState` created
- `currentActorPlayerId` set to first eligible bidder
- `minimumNextBid` set

#### On bid

- command: `PlaceAuctionBidCommand`
- validate current actor, eligibility, funds, minimum bid
- update `currentBid`, `leadingPlayerId`
- remove no players
- rotate `currentActorPlayerId`
- emit `AuctionBidPlaced`

#### On pass

- command: `PassAuctionCommand`
- mark actor as passed permanently in this auction
- rotate `currentActorPlayerId`
- emit `AuctionPassed`

#### End

When only one eligible non-passed bidder remains:

- transfer property
- deduct winning bid
- clear `AuctionState`
- emit `AuctionWon`
- continue turn flow

### Example 5: Trade

#### Open

- command: `OpenTradeCommand`
- create `TradeState`
- no actual transfer yet

#### Edit

- trade editing should mutate only `TradeState.currentOffer`, not game assets

#### Submit

- command: `SubmitTradeOfferCommand`
- `decisionRequiredFromPlayerId = recipient`
- phase may become `WAITING_FOR_TRADE_RESPONSE`
- emit `TradeOfferSubmitted`

#### Accept

- validate ownership/cash/current trade state
- transfer assets and cash atomically
- clear `TradeState`
- emit `TradeAccepted`

#### Counter

- mutate offer
- flip `decisionRequiredFromPlayerId`
- emit `TradeCountered`

#### Cancel or decline

- clear `TradeState`
- emit `TradeDeclined` or `TradeClosed`

### Example 6: Autoplay Toggle

#### Human-owned seat enables autoplay

- command: `ToggleAutoplayCommand`
- validate actor controls that seat
- set seat `controlMode = AUTOPLAY`
- emit `AutoplayEnabled`

#### While autoplay is active

- application bot orchestrator may issue commands on behalf of that seat
- those commands should still identify the same `actorPlayerId`
- optional metadata can say `initiatedBy = AUTOPLAY`

#### Human resumes control

- command: `ToggleAutoplayCommand`
- set `controlMode = MANUAL`
- emit `AutoplayDisabled`

Important:

Autoplay should not replace the seat or player identity. It only changes control delegation.

## Migration Guidance

### First Implementation Slice

Best first vertical slice:

- `SessionState`
- `TurnState`
- `PendingDecision`
- `ChooseDecisionOptionCommand`
- property purchase flow
- simple rent flow

Reason:

- covers the most common pattern
- replaces popup-owned branching with authoritative decision state
- creates the foundation for debt and auction next

### Second Slice

- `DebtState`
- bankruptcy

### Third Slice

- `AuctionState`

### Fourth Slice

- `TradeState`

### Fifth Slice

- unify bots behind command application

## Explicit Deferred Decisions

These are intentionally left open for a later spec revision.

### Deferred 1: Full normalized state vs some denormalized helpers

Open question:

- should `PlayerState` contain fully derived convenience fields like monopolies held, rent danger, and net worth
- or should those remain projections

Recommendation:

- keep authoritative state lean
- derived values belong in projections unless rules truly require them

### Deferred 2: Mid-animation authority

Open question:

- should `TurnPhase.ANIMATING_MOVEMENT` exist in authoritative state
- or should movement animation be presentation-only between two accepted snapshots

Recommendation:

- keep rule phases authoritative
- keep detailed interpolation presentation-only
- only store animation-related authoritative phases where command availability depends on them

### Deferred 3: Client identity model

Open question:

- how much of client/account identity do we need before networking work starts

Recommendation:

- keep it abstract now
- do not block separation work on account design

## Open Questions For Review

These need product/UX decisions before implementation starts.

1. Should auction turn order and bid increments be fully visible as authoritative state in the client, or is some of
   that acceptable as derived UI text only?

## Recommended Next Review Step

Before implementation, the next useful review artifact would be:

- a command-by-command matrix:
    - allowed phase
    - allowed actor
    - authoritative mutations
    - emitted events
    - view hints

That would let the first migration slice be implemented with much lower ambiguity.

## Decisions Locked So Far

These were reviewed and can now be treated as working assumptions for the first implementation plan.

### Language

- `ChangeLanguage` is client-local state
- it should not be part of authoritative session commands
- different clients may render the same session in different languages

### Trade Editing

- first implementation should use one generic `EditTradeOfferCommand`
- the payload can act as a trade-offer patch
- more explicit trade-edit commands can be introduced later if needed

### Autoplay Interrupt

- the human may interrupt autoplay immediately at any time
- this includes situations where the seat already has an open pending decision
- practical implication:
    - seat control delegation changes immediately
    - the pending decision remains authoritative
    - responsibility for answering it transfers back to the human-controlled seat
    - if autoplay is enabled, the bot may answer pending decisions immediately without grace delay

### Debt Resolution Confirmation

- debt remediation remains explicit
- mortgage/building-sale actions do not auto-pay the debt the moment cash becomes sufficient
- the debtor must still issue a separate `PayDebtCommand`
- this matches current gameplay better and keeps debt flow easier to reason about

### Auction End State

- auctions should enter an explicit terminal resolution state before fully returning to normal turn flow
- this gives presentation and later networking more room for:
    - winner emphasis
    - animation timing
    - acknowledgement UX if desired
- practical implication:
    - winning the auction and finishing the auction are separate conceptual steps

### Property Purchase Commands

- property purchase should use explicit commands from the first migration slice:
    - `BuyPropertyCommand`
    - `DeclinePropertyCommand`
- do not route initial property purchase through a generic `ChooseDecisionOptionCommand`
- generic decision commands can still exist for lower-value generic decisions later

### Recent Messages

- `recent messages` are not authoritative state
- they should be derived from domain events / projection
- exact wording may differ by client and language
- save/load should preserve enough event context to rebuild a reasonable recent history, but not require exact text
  preservation
