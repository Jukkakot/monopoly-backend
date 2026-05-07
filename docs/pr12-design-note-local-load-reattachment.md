# PR12 Design Note: Local Load Reattachment

## Purpose

PR12 is the first user-facing load/resume slice on top of PR9 and PR11.

Its purpose is narrow:

- take an authoritative saved session
- rebuild runtime objects
- reattach local presentation/services
- resume the game locally from that restored session

This is still local-only. It is the last practical proving step before backend extraction.

## Goal

After PR12:

- local client can load a saved snapshot into a real playable session
- `Game` reattaches to restored runtime/session state instead of always bootstrapping a fresh game
- command handlers and presentation adapters operate against restored authoritative state
- pending decision / auction / debt / trade / continuation resume correctly after load

## Main Work

### 1. Introduce a local load bootstrap path

Likely outcome:

- `Game` gets a "new session" bootstrap path and a "reattach restored session" bootstrap path

### 2. Rehydrate runtime and authoritative service together

Need a small coordinator that does all of these in one place:

- load snapshot
- restore runtime objects
- seed `SessionApplicationService`
- rebuild presentation adapters
- sync transient UI from restored state

### 3. Keep `Game` as shell

Do not let `Game` become save/load authority.

Instead:

- add a dedicated restore coordinator / bootstrap helper
- let `Game` consume the result

## Why PR12 Exists Separately

This split is deliberate:

- PR11 is about authoritative continuation correctness
- PR12 is about local presentation/runtime reattachment

Keeping them separate reduces risk and keeps failures easier to interpret.

## Acceptance Criteria

PR12 is done when:

- a saved local game can be loaded
- gameplay continues correctly
- no fresh-game-only assumptions remain in the reattached path

## Recommended Next Step After PR12

After PR12, the remaining step before backend implementation is mostly operational:

- choose the first server session host boundary
- choose command transport
- start PR10 implementation
