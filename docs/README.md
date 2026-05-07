# Docs Guide

## Purpose

This file is the entry point for the `docs` folder.

Use it to:

- understand which documents matter
- read them in the right order
- continue the architecture/migration work without reopening the same discussion every time

## Current Status

The docs folder is now organized around one main program:

- first separate UI/presentation from game authority locally
- then move toward persistence and later server extraction

The main local-separation wave is already specified through PR1-PR8 design notes.
Persistence/server follow-up is now specified through PR9-PR12 notes.

Current implementation reality:

- the original PR-plan documents are still useful, but they are no longer the exact status tracker
- the most accurate current-state docs are now:
  - [current-architecture-status.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/current-architecture-status.md)
  - [architecture-overview-diagrams.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/architecture-overview-diagrams.md)
  - [backend-ready-fast-track-plan.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/backend-ready-fast-track-plan.md)

Folder convention:

- implemented historical plan slices now live under
  [completed-plans](/E:/Documents/ProcessingProjects/MonopolyApp/docs/completed-plans)
- still-active forward-looking plan docs remain in the top-level `docs` folder

## Read Order

If starting fresh, read in this order.

### 1. High-level direction

- [architecture-separation-and-server-plan.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/architecture-separation-and-server-plan.md)
- [architecture-overview-diagrams.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/architecture-overview-diagrams.md)
- [current-architecture-status.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/current-architecture-status.md)
- [backend-ready-fast-track-plan.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/backend-ready-fast-track-plan.md)

Read this first to understand:

- why the work starts locally instead of from a server
- what the target architecture is
- what the large migration phases were originally
- what has already actually been implemented
- what the fastest remaining path to backend-ready architecture is
- and, if you want the fast visual version, the high-level diagrams

### 2. Authoritative model

- [session-state-command-spec.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/session-state-command-spec.md)

Read this next to understand:

- authoritative session state
- commands
- pending decisions
- events

### 3. Command behavior rules

- [command-matrix-first-slices.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/command-matrix-first-slices.md)

Read this to understand:

- who can issue which command
- which phase a command is valid in
- what each command mutates

### 4. Current-code migration map

- [migration-map-local-separation.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/migration-map-local-separation.md)

Read this to understand:

- how the current codebase maps to the target architecture
- which existing classes are legacy bridges
- which ones should shrink or disappear

### 5. Program index

- [separation-program-index.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/separation-program-index.md)

Use this as the original migration-program index:

- which PRs exist
- which documents define them
- which phases were originally planned
- how the old PR-wave maps to current status

### 6. PR design notes

Then read the PR notes in order:

- [pr1-design-note-session-state-seam.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/completed-plans/pr1-design-note-session-state-seam.md)
- [pr2-design-note-property-purchase-slice.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/completed-plans/pr2-design-note-property-purchase-slice.md)
- [pr3-design-note-rent-and-debt-opening.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/completed-plans/pr3-design-note-rent-and-debt-opening.md)
- [pr4-design-note-debt-remediation.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/completed-plans/pr4-design-note-debt-remediation.md)
- [pr5-design-note-auction-flow.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/completed-plans/pr5-design-note-auction-flow.md)
- [pr6-design-note-trade-flow.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/completed-plans/pr6-design-note-trade-flow.md)
- [pr7-design-note-bot-command-unification.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/completed-plans/pr7-design-note-bot-command-unification.md)
- [pr8-design-note-game-presentation-shell-cleanup.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/completed-plans/pr8-design-note-game-presentation-shell-cleanup.md)
- [pr9-design-note-persistence-ready-session.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/pr9-design-note-persistence-ready-session.md)
- [pr10-design-note-server-extraction-mvp.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/pr10-design-note-server-extraction-mvp.md)
- [pr11-design-note-turn-continuation-state.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/pr11-design-note-turn-continuation-state.md)
- [pr12-design-note-local-load-reattachment.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/pr12-design-note-local-load-reattachment.md)

## Short Version

If you do not want to reread everything every time, the minimum useful set is:

1. [current-architecture-status.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/current-architecture-status.md)
2. [backend-ready-fast-track-plan.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/backend-ready-fast-track-plan.md)
3. [architecture-overview-diagrams.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/architecture-overview-diagrams.md)
4. optionally [migration-map-local-separation.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/migration-map-local-separation.md) if touching `Game`, desktop shell, popup flow, or controllers

## How To Continue This Plan

When continuing later, the right shorthand is:

- `continue the separation program from PR1`
- `continue from PR3`
- `implement PR2 according to docs`
- `plan the next PR after PR6`

That should be enough context together with this docs set.

Useful later shorthands:

- `continue from PR9`
- `continue from PR11`
- `start the persistence wave`
- `continue from the server MVP plan`
- `continue toward backend-ready architecture`
- `follow the fast-track backend plan`

## Implementation Rule

While following this plan:

- do not add new gameplay authority
  into [Game.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/Game.java)
- do not add new gameplay authority
  into [PopupService.java](/E:/Documents/ProcessingProjects/MonopolyApp/src/main/java/fi/monopoly/components/popup/PopupService.java)
- prefer moving rule authority into command/state/application types
- keep each implementation PR limited to one defined slice

## What Is Legacy / Secondary

These docs are still useful, but not the primary reading path for local separation:

- [networking-mvp-plan.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/networking-mvp-plan.md)

Reason:

- it becomes important later, after the local separation wave is sufficiently implemented

## Recommended Next Action

The documentation is now in a state where the next sensible move is:

- use [current-architecture-status.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/current-architecture-status.md) as the truth source for what is already done
- use [backend-ready-fast-track-plan.md](/E:/Documents/ProcessingProjects/MonopolyApp/docs/backend-ready-fast-track-plan.md) as the default path forward if the goal is to reach the backend-ready target as fast as possible

After that:

- continue using the older PR docs as subsystem references, not as the live status tracker
