# PR7 Design Note: Bot Command Unification

## Purpose

This document defines the next consolidation wave after the main subsystem migrations.

By PR7, the main stateful gameplay subsystems should already have command-driven authority:

- property purchase
- rent/debt
- auction
- trade

PR7 should unify computer-player execution around that command boundary so bots are no longer special imperative callers
of legacy UI/runtime methods.

## PR7 Goal

After PR7:

- bots decide on actions by reading projected authoritative state
- bots execute actions by emitting the same command types humans use
- `ComputerTurnContext` no longer exposes imperative gameplay mutations as the primary API
- popup-specific bot actions are minimized or removed in favor of state/command responses

This PR should make bot execution architecture consistent, even if heuristics remain unchanged.

## Explicit Non-Goals For PR7

PR7 must not:

- redesign bot heuristics broadly
- rebalance strong/smoke behavior
- redesign autoplay UX modes deeply
- move bots to a server yet

If a change does not reduce bot reliance on imperative context methods, it is out of scope.

## Current Problem

Current bot control is still centered around imperative `ComputerTurnContext` methods such as:

- `acceptActivePopup()`
- `declineActivePopup()`
- `sellBuilding(...)`
- `buyBuildingRound(...)`
- `toggleMortgage(...)`
- `retryPendingDebtPayment()`
- `declareBankruptcy()`
- `rollDice()`
- `endTurn()`

See:

- [ComputerTurnContext.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/computer/ComputerTurnContext.java)
- [StrongComputerStrategy.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/computer/StrongComputerStrategy.java)

This means:

- bots are still coupled to legacy execution surfaces
- bot execution is not yet cleanly transferable to future backend/application ownership

## PR7 Goal In Concrete Terms

Bots should do this:

1. inspect projected authoritative state
2. produce a `SessionCommand`
3. application handles the command
4. bot waits for next state/update cycle

That should replace the current pattern:

1. inspect `GameView`
2. call imperative context method that mutates local game flow

## Recommended New Types

### `BotCommandPlanner`

Recommended package:

- `fi.monopoly.application.bot.BotCommandPlanner`

Purpose:

- maps current bot strategy outputs into explicit commands

### `BotStepResult`

Fields:

- `SessionCommand command`
- `ComputerDecision decision`

Purpose:

- preserve existing decision/reason logging while changing execution path

### `BotOrchestrator`

Recommended package:

- `fi.monopoly.application.bot.BotOrchestrator`

Responsibilities:

- determine whether a seat/player is bot-controlled right now
- ask the appropriate strategy/planner for next command
- submit the command to `SessionApplicationService`
- handle delays/timing externally to domain logic

## Existing Types To Refactor

### [ComputerTurnContext.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/computer/ComputerTurnContext.java)

Target after PR7:

- should stop being a mutation-heavy imperative surface

Recommended replacement direction:

- read-only context for current projected state, if still needed
- command emission instead of direct action methods

Possible transitional form:

- `GameView gameView()`
- `PlayerView currentPlayerView()`
- `void submit(SessionCommand command)`

But the end goal should be even narrower:

- strategies return commands rather than submit them directly

### [ComputerAction.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/computer/ComputerAction.java)

Current shape is still oriented toward imperative local actions.

Target after PR7:

- either de-emphasize it to a logging/category role
- or evolve it into a decision taxonomy that maps to commands

It should no longer be the execution mechanism itself.

### [StrongComputerStrategy.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/computer/StrongComputerStrategy.java)

Target after PR7:

- inspect current projected state
- return next `SessionCommand` proposal instead of calling mutation methods directly

Same for smoke/fallback strategies.

## Recommended Migration Strategy

### Step 1

Keep heuristics, change execution shape.

Meaning:

- do not rewrite buy/build/debt/trade heuristics yet
- only rewrite how their chosen action is executed

### Step 2

Introduce a translation layer from current strategy decisions to commands.

Examples:

- property purchase accept -> `BuyPropertyCommand`
- property purchase decline -> `DeclinePropertyCommand`
- debt payment -> `PayDebtCommand`
- build round -> existing build command
- auction bid/pass -> auction commands
- trade accept/decline/counter -> trade commands
- end turn -> `EndTurnCommand`

### Step 3

Remove direct imperative bot execution calls one subsystem at a time.

Do not keep both paths alive indefinitely.

## Subsystem Mapping

### Property purchase

Current bot behavior:

- accept/decline popup

PR7 target:

- if pending decision is `PROPERTY_PURCHASE`, bot chooses:
    - `BuyPropertyCommand`
    - or `DeclinePropertyCommand`

### Debt

Current bot behavior:

- sell/mortgage/retry/declare via imperative context methods

PR7 target:

- `SellBuildingForDebtCommand`
- `SellBuildingRoundsAcrossSetForDebtCommand`
- `MortgagePropertyForDebtCommand`
- `PayDebtCommand`
- `DeclareBankruptcyCommand`

### Auction

Current bot behavior:

- implicit resolver-driven bidding

PR7 target:

- `PlaceAuctionBidCommand`
- `PassAuctionCommand`

### Trade

Current bot behavior:

- controller-centric proposal/response orchestration

PR7 target:

- open/edit/submit/accept/decline/counter commands

### Turn flow

Current bot behavior:

- `rollDice()`
- `endTurn()`

PR7 target:

- `RollDiceCommand`
- `EndTurnCommand`

## Autoplay Relationship

PR7 should treat autoplay as a seat-control choice, not a separate rule path.

Meaning:

- autoplay uses the same bot planner/orchestrator machinery as true bot seats
- only the seat-control source changes

This is a major benefit of command unification.

## Current-Class Change Guidance

### [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)

Allowed:

- keep bot timing / scheduling for now
- ask `BotOrchestrator` for next command
- dispatch command through application service

Not allowed:

- keep expanding direct imperative bot action methods

### [StrongComputerStrategy.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/computer/StrongComputerStrategy.java)

Allowed:

- preserve heuristics and reasoning strings

Not allowed:

- direct gameplay mutation calls after PR7

## Tests Required For PR7

### Application/bot tests

Add tests for:

- property purchase bot decision yields property-purchase command
- debt bot decision yields debt remediation command
- auction bot decision yields bid/pass command
- trade bot decision yields trade command
- end-turn bot decision yields `EndTurnCommand`

### Integration tests

Add tests for:

- autoplay seat can drive commands through same bot path
- bot command path produces same gameplay outcomes as earlier imperative path

## Exact Files Likely Added In PR7

- `src/main/java/fi/monopoly/application/bot/BotOrchestrator.java`
- `src/main/java/fi/monopoly/application/bot/BotCommandPlanner.java`
- `src/main/java/fi/monopoly/application/bot/BotStepResult.java`

## Exact Files Likely Modified In PR7

- [ComputerTurnContext.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/computer/ComputerTurnContext.java)
- [StrongComputerStrategy.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/computer/StrongComputerStrategy.java)
- smoke/fallback bot strategy classes
- [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)

## Review Checklist For PR7

PR7 is acceptable only if:

- bots execute through commands
- autoplay uses the same path
- imperative bot mutation methods are gone or clearly transitional-only
- command/result logging remains understandable

## Recommended Next Step After PR7

PR8 should be:

- aggressive `Game` presentation-shell cleanup

At that point the main remaining architecture debt is concentrated in the god object and the legacy runtime bridge.
