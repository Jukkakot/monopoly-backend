# PR4 Design Note: Debt Remediation

## Purpose

This document defines the fourth migration PR after:

- PR1: session-state seam
- PR2: property purchase slice
- PR3: rent and debt opening

PR4 should complete the debt flow by moving debt remediation behind explicit commands.

That includes:

- mortgage actions
- building sale actions
- explicit `PayDebtCommand`
- bankruptcy declaration

After PR4, debt should no longer be a legacy popup/controller-owned subsystem. It should be an authoritative state
machine rendered by the UI.

## PR4 Goal

After PR4:

- debt remediation is command-driven
- debt UI renders available actions from `DebtStateModel`
- `DebtController` is no longer the authority for debt progression
- `PayDebtCommand` is the explicit resolution step
- bankruptcy declaration is also command-driven

This is the PR that should finish the first full “authoritative multi-step subsystem” in the migration.

## Explicit Non-Goals For PR4

PR4 must not:

- migrate auction bidding fully
- migrate trade negotiation
- redesign deed UI broadly
- migrate all build/unmortgage interactions outside debt context
- fully replace every legacy property helper implementation

If a change does not support debt remediation authority, it is out of scope.

## Required Preconditions

PR4 assumes:

- PR3 has already made debt opening authoritative
- `SessionState.activeDebt` exists
- debt UI visibility is already driven by authoritative state
- rent/debt opening no longer depends on popup callbacks

Without those, PR4 becomes a mixed migration and should not be attempted yet.

## Exact Commands For PR4

### `PayDebtCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String debtId`

Meaning:

- debtor explicitly requests that the currently available cash be used to settle the active debt

Validation:

- actor must equal debtor
- debt must exist
- debt id must match
- player must currently have enough cash to pay `amountRemaining`

### `MortgagePropertyForDebtCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String debtId`
- `String propertyId`

Validation:

- actor is debtor
- debt exists
- property belongs to debtor
- property is mortgageable under current rules
- property is not already mortgaged

### `SellBuildingForDebtCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String debtId`
- `String propertyId`
- `int count`

Notes:

- `count` should support single-property sale first
- if we later want “sell rounds across set” as a debt command, that can be a separate command

Validation:

- actor is debtor
- debt exists
- property belongs to debtor
- property is a street property
- sale is legal under even-selling rules
- count is positive

### `SellBuildingRoundsAcrossSetForDebtCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String debtId`
- `String propertyId`
- `int rounds`

Why this should exist:

- current UX already supports even sell rounds across set
- removing that in migration would be a regression

### `DeclareBankruptcyCommand`

Fields:

- `String sessionId`
- `String actorPlayerId`
- `String debtId`

Validation:

- actor is debtor
- active debt exists
- debt id matches

## Existing Types To Extend In PR4

### `DebtStateModel`

PR4 should extend it slightly:

- `PaymentObligation obligation`
- `List<DebtAction> allowedActions`
- `boolean bankruptcyRisk`
- `int currentCash`
- `int estimatedLiquidationValue`

Notes:

- `currentCash` and `estimatedLiquidationValue` are acceptable here as convenience fields for UI and bots
- they are still derivable, but PR4 benefits from having them explicit in debt state

### `DebtAction`

Ensure it supports:

- `PAY_DEBT_NOW`
- `MORTGAGE_PROPERTY`
- `SELL_BUILDING`
- `SELL_BUILDING_ROUNDS_ACROSS_SET`
- `DECLARE_BANKRUPTCY`

## Current Legacy Flow To Replace

Current legacy flow is centered around:

- [DebtController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/DebtController.java)
- current property methods like:
    - mortgage/unmortgage
      on [Property.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/properties/Property.java)
    - sell houses / sell rounds
      on [StreetProperty.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/properties/StreetProperty.java)
- debt retry + bankruptcy branching controlled in legacy imperative flow

Problem:

- remediation side effects and debt state progression are still tightly coupled to UI and the legacy controller

PR4 should make debt remediation work like:

- UI dispatches explicit debt command
- application validates it against authoritative `DebtStateModel`
- legacy helpers may still perform the low-level property/player mutation temporarily
- resulting authoritative state is re-projected and becomes the source of truth

## Recommended Transitional Architecture

### New application component

#### `DebtRemediationCommandHandler`

Responsibilities:

- validate all debt remediation commands
- dispatch legal legacy-backed mutations through a gateway
- update authoritative debt/session state
- decide whether debt remains open, becomes payable, resolves, or leads to bankruptcy progression

This should become the main owner of debt progression in PR4.

### New legacy bridge

#### `LegacyDebtRemediationGateway`

Responsibilities:

- mortgage property through current property/player model
- sell building(s) through current street property model
- declare bankruptcy through current asset transfer/release behavior
- expose enough result information for re-projection

Why:

- lets PR4 use existing low-level rule logic safely
- localizes dependence on current `Player`, `Property`, and `StreetProperty`

### Presentation bridge

#### `DebtActionDispatcher`

Purpose:

- map current debt UI controls to explicit debt commands

This should live in presentation space, not inside domain/application.

Responsibilities:

- when user clicks mortgage/sell/pay/bankruptcy actions, dispatch the explicit command
- no direct rule mutation in the UI layer

## Exact State Transition Rules

### Mortgage command accepted

Mutation result:

- selected property becomes mortgaged
- debtor cash increases by mortgage value
- `DebtStateModel.currentCash` and `estimatedLiquidationValue` refresh

Then:

- if debt is still not affordable:
    - debt remains active
- if debt is now affordable:
    - debt still remains active until explicit `PayDebtCommand`

Events:

- `PropertyMortgaged`
- optionally `DebtProgressChanged`

View hints:

- refresh property card
- keep debt panel open

### Sell building command accepted

Mutation result:

- property development decreases legally
- debtor cash increases by sale proceeds
- debt state refreshes

Then:

- same rule as above:
    - debt does not auto-resolve

Events:

- `BuildingSold`
- optionally `DebtProgressChanged`

View hints:

- refresh property development visuals
- keep debt panel open

### Pay debt command accepted

Mutation result:

- transfer `amountRemaining` from debtor to creditor
- clear `activeDebt`
- turn phase returns to next post-debt phase:
    - usually `WAITING_FOR_END_TURN`

Events:

- `DebtResolved`

View hints:

- close debt panel
- refresh cash views

### Declare bankruptcy command accepted

Mutation result:

- remove debtor from active players
- transfer or release assets according to creditor type
- clear `activeDebt`
- if one player remains:
    - session becomes `GAME_OVER`
    - set winner
- otherwise:
    - continue to next legal turn/player flow

Events:

- `BankruptcyDeclared`
- optionally:
    - `WinnerDeclared`
    - `AuctionStarted` for bank-release follow-up

View hints:

- close debt panel
- show bankruptcy/winner UI as needed

## DebtController Migration Rule

This is the key rule for PR4:

- [DebtController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/DebtController.java)
  must stop owning debt progression

After PR4 it may still temporarily:

- host some legacy helper methods
- help bridge to current bankruptcy asset-transfer logic

But it should no longer be:

- the source of truth for whether debt exists
- the owner of retry/pay/bankruptcy branching

Strong recommendation:

- after PR4, consider renaming or shrinking it aggressively to make that reduced role visible

## Current-Class Change Guidance

### [DebtController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/DebtController.java)

PR4 target:

- move branching logic out
- reduce to helper/adapter or remove substantial logic if feasible

### [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)

Allowed:

- render debt UI based on authoritative state
- dispatch debt commands

Not allowed:

- debt remediation branching or validation in `Game`

### [StreetProperty.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/properties/StreetProperty.java)

Allowed:

- legacy low-level sell/build validation can still be reused through gateway

Not allowed:

- presentation-triggered debt authority to persist here

### [Player.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Player.java)

Allowed:

- reuse current asset transfer / liquidation helpers through gateway

Not allowed:

- new command validation logic in `Player`

## Bot Behavior In PR4

This is an important milestone.

After PR4, bots should be able to remediate debt using explicit commands too.

Required:

- bot debt actions must go through:
    - mortgage command
    - sell building command
    - pay debt command
    - bankruptcy command

Temporary allowance:

- selection heuristics may still live in current bot strategy classes
- but the action execution must use the command path

This will be one of the first strong proofs that the command boundary is real.

## Tests Required For PR4

### Application-level tests

Add tests for:

- mortgage command increases cash and leaves debt open
- sell building command increases cash and leaves debt open
- `PayDebtCommand` rejects if cash still insufficient
- `PayDebtCommand` resolves debt when cash is enough
- bankruptcy command clears debt and removes player

### Integration tests

Add tests for:

- debt UI commands dispatch explicit remediation commands
- bot debt remediation uses command path
- legacy controller no longer owns the main debt progression branch

### Regression tests to keep green

- debt-related gameplay tests
- bankruptcy tests
- bot simulation tests
- turn control tests

## Exact Files Likely Added In PR4

- `src/main/java/fi/monopoly/application/command/MortgagePropertyForDebtCommand.java`
- `src/main/java/fi/monopoly/application/command/SellBuildingForDebtCommand.java`
- `src/main/java/fi/monopoly/application/command/SellBuildingRoundsAcrossSetForDebtCommand.java`
- `src/main/java/fi/monopoly/application/session/DebtRemediationCommandHandler.java`
- `src/main/java/fi/monopoly/presentation/session/LegacyDebtRemediationGateway.java`
- `src/main/java/fi/monopoly/presentation/session/DebtActionDispatcher.java`

## Exact Files Likely Modified In PR4

- [DebtController.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/payment/DebtController.java)
- [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)
- [StreetProperty.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/properties/StreetProperty.java)
- [Player.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Player.java)
-
possibly [Property.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/properties/Property.java)
if a cleaner mortgage seam is needed

## Review Checklist For PR4

PR4 is acceptable only if:

- all debt remediation actions are command-driven
- debt remains open until explicit `PayDebtCommand`
- `DebtController` is no longer the authority for debt flow
- bots use the same remediation action path as humans
- no unrelated auction/trade migration leaked into the PR

## Main Risk In PR4

The main risk is overstuffing the PR with too many property/deed side changes.

Mitigation:

- keep focus strictly on debt remediation authority
- allow low-level legacy property helpers to survive through gateway usage
- defer deed/general mortgage UX cleanup to later work

## Recommended Next Step After PR4

PR5 should be:

- full auction flow migration

That is the next subsystem where decline/pass/bid sequencing is still too UI-owned.
