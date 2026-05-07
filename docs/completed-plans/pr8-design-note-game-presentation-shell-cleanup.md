# PR8 Design Note: Game Presentation Shell Cleanup

## Purpose

This document defines the next consolidation PR after the main subsystem migrations and bot command unification.

By PR8, the major gameplay authority should already be out of `Game`:

- property purchase
- debt
- auction
- trade
- bot command execution

PR8 should aggressively
shrink [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java) into a
real presentation shell.

## PR8 Goal

After PR8:

- `Game` is mostly:
    - draw/update loop
    - input collection
    - view-state rendering
    - animation timing
    - local UI-only concerns
- gameplay authority is no longer meaningfully split across `Game`
- `Game` no longer acts as a hidden application service

This is the PR where the earlier subsystem migrations pay off architecturally.

## Explicit Non-Goals For PR8

PR8 must not:

- redesign the whole UI
- change gameplay rules
- redesign rendering performance deeply
- start server extraction yet

If a change does not reduce `Game` toward a shell, it is out of scope.

## Current Problem

Even after subsystem extraction
work, [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java) still
mixes:

- Processing draw/update responsibilities
- button layout and visibility
- local bot scheduling
- view projection caching
- command gating
- residual gameplay orchestration
- direct awareness of legacy controller flows

PR8 should make the architectural role explicit:

- `Game` is presentation
- `SessionApplicationService` is application
- state models are authority

## Recommended Refactoring Targets

### Extract from `Game`

#### `GameUiController`

Responsibilities:

- map local input to commands
- coordinate popup adapters and UI adapters
- hold UI-only interaction state

#### `GameViewPresenter`

Responsibilities:

- derive/render high-level view state segments for board/sidebar/popups
- centralize view composition

#### `BotTurnScheduler`

Responsibilities:

- bot timing and pacing
- not bot decision logic itself

#### `SessionViewFacade`

Responsibilities:

- expose current projected state + available UI decisions to `Game`

This may be a thin wrapper around `SessionApplicationService` + projector/view adapters.

## What Should Stay In `Game`

Keep only:

- Processing event hooks
- draw loop
- animation tick update
- high-level delegation to presenter/controller

If a method in `Game` decides gameplay outcomes or validates commands, it should be suspect by default.

## What Should Leave `Game`

Remove or delegate over time:

- direct gameplay branching
- subsystem-specific command logic
- bot strategy interaction details
- popup decision ownership
- debt/trade/auction special-case rule flow
- session-state mutation logic

## Relationship With `MonopolyRuntime`

PR8 does not need to
delete [MonopolyRuntime.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/MonopolyRuntime.java),
but it should narrow what `Game` gets from it.

Target direction:

- `MonopolyRuntime` remains bootstrap/infrastructure/presentation context
- application/domain do not depend on it
- `Game` depends on runtime only for UI resources and local services

## Suggested File Moves / New Types

### New files likely

- `src/main/java/fi/monopoly/presentation/game/GameUiController.java`
- `src/main/java/fi/monopoly/presentation/game/GameViewPresenter.java`
- `src/main/java/fi/monopoly/presentation/game/BotTurnScheduler.java`
- `src/main/java/fi/monopoly/presentation/game/SessionViewFacade.java`

### Existing files likely modified heavily

- [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)

### Existing files likely only lightly touched

- popup service / popup adapters
- application service access points

## Refactoring Strategy

### Step 1

Move view composition code out first.

Why:

- lower risk than moving input flow and scheduling first

### Step 2

Move command dispatch/input flow into `GameUiController`.

### Step 3

Move bot pacing/scheduling into `BotTurnScheduler`.

### Step 4

Strip `Game` down to glue only.

## Tests Required For PR8

### Structural tests / regressions

Keep:

- turn control tests
- popup layout tests
- bot simulation tests

### New tests

Add tests where feasible for:

- `GameUiController` input-to-command mapping
- bot scheduler timing behavior
- presenter output for key UI states

## Review Checklist For PR8

PR8 is acceptable only if:

- `Game` clearly reads as presentation shell
- gameplay branching is no longer concentrated there
- subsystem authority remains outside `Game`
- no large behavior regressions were introduced during refactor

## Recommended Next Step After PR8

After PR8, the next major milestone planning should shift toward:

- persistence-ready authoritative session state
- then server extraction

At that point the local separation program is mature enough to start the backend move without dragging UI authority
along.
