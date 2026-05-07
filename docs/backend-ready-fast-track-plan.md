# Backend-Ready Fast-Track Plan

## Purpose

This document defines the fastest realistic path from the current codebase to the target backend-ready architecture.

The goal is not “perfect cleanup first”.

The goal is:

- reach a host/client architecture quickly
- preserve current local desktop play during migration
- avoid rewriting rule logic again
- make the first backend extraction path narrow and boring

## Current Starting Point

The repo already has enough local separation that we should stop treating backend extraction as a distant future phase.

We already have:

- application/session authority
- separated desktop shell/runtime/session/UI packages
- bot/turn/session coordinators
- persistence and restoration groundwork

So the fastest path is no longer “more generic cleanup”.

The fastest path is to create a stable host/client seam and move authority behind it.

## Target End State

The target backend-ready architecture is:

- client renders and submits intents only
- host owns authoritative session execution
- bots run in the host
- snapshots/view state flow from host to client
- save/load/reconnect are host concerns, not special client-only flows

## Fastest Realistic Plan

### Phase 1: Introduce a narrow client session interface

Create a client-facing interface that the Processing client can use without knowing whether the host is local or remote.

Minimum shape:

- `connect`
- `disconnect`
- `currentSnapshot` or `currentViewState`
- `submit(command)`
- `addListener(snapshot/update listener)`
- `sessionStatus`

Rules:

- the interface must not expose Processing types
- the interface must not expose legacy runtime objects
- the interface must be stable enough to support both embedded and remote implementations

Why this is the fastest first step:

- it gives one seam for the rest of the migration
- it prevents more local-only orchestration from spreading
- it allows backend work to begin before every legacy detail is cleaned up

### Phase 2: Move bot ownership behind the host interface

After the client session interface exists, move bot scheduling/execution so that:

- bot decisions are requested by the host
- bot commands are submitted through the same command path as human commands
- client no longer “drives” bot turns as an architectural responsibility

Embedded local mode can still run bots in the same process, but the ownership must be host-side.

This is the key architectural step that makes later remote hosting straightforward.

### Phase 3: Create an embedded local session host

Before a real network backend, add a true embedded host implementation in-process.

That host should own:

- session application service
- command serialization/queueing
- bot scheduler
- persistence entry points

The current desktop app should then talk to that embedded host through the new client session interface.

This is the fastest route because it proves the architecture without network complexity.

### Phase 4: Make the desktop client render from host-approved state

Once the embedded host exists, keep shrinking client authority:

- `Game` and runtime/UI code render approved state
- local UI submits commands/intents
- local runtime reconstruction becomes a client adapter

The client may still keep animation/transient UI state, but not rule authority.

### Phase 5: Extract remote host transport

Only after phases 1-4 are stable:

- add `server.session`
- add HTTP/WebSocket transport
- add remote implementation of the same client session interface

This should be the smallest possible transport MVP:

- create/join/connect
- submit command
- receive full snapshot/view updates
- reconnect to latest session state

## Proposed Concrete Package Direction

To reach the target quickly, add explicit package roots instead of hiding everything under desktop packages forever.

### New client-side package direction

- `fi.monopoly.client.session`
- `fi.monopoly.client.transport`
- `fi.monopoly.client.presentation`

### New host/server-side package direction

- `fi.monopoly.host.session`
- `fi.monopoly.host.bot`
- `fi.monopoly.host.persistence`

### Keep existing packages as transitional internals

- `fi.monopoly.presentation.game.desktop.*`
- `fi.monopoly.presentation.session.*`

These should gradually become adapters around the newer client/host seams, not long-term ownership layers.

## The ASAP Backlog

If we want the fastest practical route, this is the order I would follow.

### Slice 1: Host/client seam

- define `ClientSession`-style interface
- define snapshot/update payload used by the client
- adapt current local desktop flow to use the interface in embedded mode

### Slice 2: Embedded host

- create `EmbeddedSessionHost`
- move command execution ownership there
- move bot scheduling ownership there
- let desktop app connect to it instead of building authority directly

### Slice 3: Client adapter layer

- convert current desktop shell to a client adapter around `ClientSession`
- keep runtime reconstruction local
- reduce direct use of application/session services from `Game`-side orchestration

### Slice 4: Remote transport MVP

- implement remote host around the same host model
- add HTTP/WebSocket session APIs
- add remote `ClientSession` implementation
- verify local embedded and remote modes both work with the same client presentation code

## What Not To Spend Too Much Time On First

If speed to backend-ready architecture matters, avoid over-investing in these before the host/client seam exists:

- more micro-cleanup of `Game` with no boundary change
- perfecting every desktop package name
- replacing every transitional adapter before host extraction starts
- making transport diffs/event streams before full snapshots work
- rebuilding all tests around final abstractions too early

Those can all come later.

## Architectural Rule For Fast Progress

From this point on, any new work should answer this question:

“Would this code still make sense if authoritative session execution moved behind an embedded or remote host?”

If the answer is no, the code probably belongs on the wrong side of the future boundary.

## Recommended Immediate Next Milestones

### Milestone 1

Introduce the client session interface and make local desktop mode consume it.

### Milestone 2

Move bot ownership and command serialization behind an embedded host.

### Milestone 3

Switch save/load and restored-session flows to operate through the host abstraction first.

### Milestone 4

Add the first remote host transport without changing client presentation architecture again.

## Success Criteria

We are effectively at backend-ready architecture when these statements are true:

- the Processing client does not own authoritative session execution
- the Processing client does not schedule bots architecturally
- local and remote play use the same client session interface
- host owns save/load/reconnect semantics
- client renders from approved state and sends commands only
