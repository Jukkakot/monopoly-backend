# Current Architecture Status

## Purpose

This document is the current-state companion to the original separation-program docs.

Use it when you want to know:

- what has already been implemented in the current codebase
- what is still legacy or transitional
- what the main blockers are before backend extraction
- which architectural seams are already good enough to build on

This is not the original plan. This is the status snapshot of the repo as it exists now.

## Executive Summary

The project is no longer in the early local-separation stage.

The codebase already has:

- authoritative session-oriented application services
- extracted desktop assembly, shell, runtime, session, and UI packages
- bot, turn, and session presentation coordinators separated from the old `Game` god object
- local save/load through session-oriented seams
- runtime reattachment support for restored local sessions

The project does not yet have full backend-ready architecture because:

- the desktop client still owns the authoritative application service instance locally
- legacy runtime objects are still rebuilt and mutated in-process
- `Game` is still a compatibility-heavy desktop host for tests and current orchestration
- the desktop shell still contains local-only coordination that should become client/server seams later
- the client still renders a host-provided live view in-process rather than a transport-neutral view model

## Current Implemented Shape

### What is already separated

- `client.desktop`
  - Processing app-facing shell/runtime adapters, runtime resources, explicit client-global desktop settings, and narrow rendering/runtime seams around the embedded local client session
  - desktop app bootstrap now also consumes one explicit client-host binding instead of assembling the embedded-local host shell inline inside `DesktopAppShell`
- `host.session.local`
  - embedded host-owned session lifecycle, persistence, snapshot publication, hosted-game lifecycle, and test-access seams for the in-process desktop mode
  - embedded host-owned local game loop coordination now also drives bot stepping outside the presentation frame coordinator
- `presentation.game.desktop.session`
  - session bridge and restored-session reattachment adapters around the embedded host
- `client.session.desktop`
  - desktop client-session shell/runtime glue, client-owned local session control callbacks such as save/load triggers, and the app-facing desktop session runtime port
  - client-facing `ClientSessionUpdates` now carries the transport-neutral session update stream, while host-only restore authority stays behind `SessionHost`
  - the old generic `ClientSession` type has now been removed; the client depends explicitly on a listener-based session-update gateway instead of a host-shaped session object
  - embedded desktop frame advancement now also crosses a dedicated local frame-driver seam instead of living on the client session-update gateway
  - fresh local session creation, local save/load, and persistence notices now also live behind a dedicated desktop-local session controls port instead of the transport-neutral session-update gateway
  - embedded desktop live render access now also crosses a dedicated local view port instead of living on the session-update gateway
  - the live render view type itself now also lives under `client.session.desktop` instead of the transport-neutral `client.session` package
  - the desktop app shell now owns a small client-side session model fed by `ClientSessionUpdates` listener updates, so snapshot state no longer reaches the app by raw runtime pass-through getters/listener registration
  - the desktop app shell now also owns a small client-side render model, so the runtime port no longer exposes raw live-view polling directly to the app
  - `SessionCommandPort` now defines the transport-neutral command submission and state query seam; presentation-layer session adapters (debt, auction, purchase, trade) depend on `SessionCommandPort` instead of the full `SessionApplicationService`
  - `BotTurnScheduler` no longer imports `DesktopClientSettings` directly; `skipAnimations` is now injected as a `BooleanSupplier` at construction time
  - `GameSessionStateCoordinator.onDebtStateChanged()` no longer takes `SessionApplicationService` directly; `clearDebtOverride` is passed as a `Runnable` callback
  - `SessionPresentationStatePort` introduced in `application.session` for the legacy override-state operations; `GameDesktopShellDependencies.StateAccess` now uses two narrow typed suppliers (`SessionCommandPort` + `SessionPresentationStatePort`) so shell and session coordinators no longer import `SessionApplicationService`
  - `GamePresentationFactory.Dependencies` now holds `SessionCommandPort` + narrow callbacks instead of `SessionApplicationService`
  - `SessionApplicationService` eliminated from `Game`, all assembly tunnel records (`GameDesktopAssembly`, `GameDesktopHostContext`), and `GameSessionBridge`; replaced with narrow port types
  - `SessionPaymentPort` introduced in `application.session` for the `handlePaymentRequest` seam
  - `SessionApplicationService` now only imported in `GameSessionBridgeFactory` (internal use) and `LegacySessionApplicationFactory` (construction)
- `domain.session`
  - authoritative session records and continuation state
- `application.session`
  - session-oriented orchestration and persistence-facing services
- `host.bot`
  - host-owned bot scheduling, bot turn stepping, and embedded local bot command adapters
  - desktop-local popup/trade/projected-view access now enters host bot flow through one explicit interaction adapter seam instead of direct runtime/controller references
- `presentation.game.turn`
  - turn flow orchestration
- `presentation.game.session`
  - session state projection helpers and queries
- `presentation.game.desktop.assembly`
  - desktop object graph assembly
- `presentation.game.desktop.shell`
  - host-to-coordinator orchestration
- `presentation.game.desktop.runtime`
  - lifecycle/bootstrap/debug coordination for local runtime
- `presentation.game.desktop.session`
  - session bridge, restored-session reattachment
- `presentation.game.desktop.ui`
  - frame/layout/input/presentation host responsibilities
  - dedicated UI session-controls port now isolates pause/bot-speed/language/save-load actions from broader presentation hooks

### Important current bridge classes

These are still transitional and should be treated as controlled legacy seams:

- `fi.monopoly.components.Game`
- `fi.monopoly.client.desktop.MonopolyApp`
- `fi.monopoly.client.desktop.MonopolyRuntime`
- `fi.monopoly.presentation.game.desktop.shell.GameDesktopSessionCoordinator`
- `fi.monopoly.presentation.game.desktop.shell.GameDesktopPresentationCoordinator`
- `fi.monopoly.presentation.game.desktop.session.GameSessionBridgeFactory`
- `fi.monopoly.presentation.game.desktop.runtime.GameRuntimeAssemblyFactory`
- `fi.monopoly.presentation.session.*` gateway/adapter classes

### Meaning of `Game` today

`Game` is no longer the primary place where gameplay wiring lives.

It is now mainly:

- the desktop host/compatibility surface
- the holder of current Processing-era live objects
- the entry point still expected by current tests and app bootstrap

That is much better than before, but it is still not the shape we want for a backend-ready client.

## What Is Still Too Local

### 1. Authoritative session host is still owned by the desktop process

Today the client still constructs and owns the local authoritative session service graph.

Backend-ready target:

- client talks to a session host
- session host owns command execution and bot scheduling
- client no longer creates the authoritative game service graph directly

### 2. Legacy runtime mutation still happens in-process

Today the same process still contains:

- authoritative session state
- legacy runtime objects
- UI rendering/runtime concerns

Backend-ready target:

- legacy runtime becomes a pure client-side rendering/runtime projection
- authoritative mutation stays outside the Processing host

### 3. Desktop shell boundaries are still optimized for local mode

The current shell is much cleaner than before, and the old single `GameDesktopShellCoordinator`
has already been split into explicit session and presentation coordinators. Some interfaces still
bundle:

- local runtime concerns
- session commands/state access
- client-only presentation visibility/interactivity rules

Backend-ready target:

- client command transport boundary becomes explicit
- local runtime rebuild/reattachment becomes a client concern only
- server/session-host concerns become separate package roots

The recent `client.desktop` and embedded-host moves improved this:

- `MonopolyApp` and `MonopolyRuntime` are now explicitly on the client side of the architecture
- the root package no longer acts as the owner of desktop bootstrap/runtime state
- future extraction work can now target `client.desktop` directly instead of peeling adapter classes out of `fi.monopoly`
- host tick advancement and client render access are also now explicitly different seams, which is closer to the eventual client/host split even in embedded mode
- embedded local bot stepping now runs through a host-owned loop coordinator instead of being scheduled from the presentation frame coordinator
- host-owned bot turn contexts now request projected game/player views for the actual acting player, which removes a local desktop assumption that leaked current-turn projections into debt resolution
- desktop UI session controls now cross the shell boundary through one dedicated `GameUiSessionControls` port instead of being mixed into the broader gameplay presentation hook surface
- embedded local hosting now also talks to a dedicated `DesktopHostedGame` adapter around `Game` instead of depending on `Game` itself as the hosted-session interface
- the embedded host now exposes the client-facing session seam directly, so the extra `LocalDesktopClientSession` forwarding adapter is no longer in the path
- compatibility-heavy test inspection on `Game` is now also being centralized behind one explicit test facade instead of scattered private reflection hooks
- desktop app-shell persistence/runtime helpers now also receive the active runtime explicitly from `DesktopRuntimeBridge` instead of reading it through the global runtime singleton
- `DesktopAppShell` now also depends on a bundled client-host binding plus narrow `DesktopRuntimeAccess` port instead of directly owning the embedded local host shell/bootstrap graph
- desktop input/event dispatch now also reads the active event bus through the app-shell/runtime seam instead of a global runtime lookup inside the Processing observer base class
- `MonopolyApp` no longer installs a placeholder global runtime during construction; the desktop runtime now appears only from explicit bootstrap/test initialization paths
- shared rendering helpers no longer depend on a global current-app context; callers now pass an explicit rendering context instead
- street/utility property runtime-dependent checks now also resolve through the owning player's runtime/session context instead of reading the global runtime singleton

### 4. Tests still lean on local host internals

Many current tests still use package-visible access to:

- `players()`
- `dices()`
- `animations()`
- `getBoard()`
- `debtController()`

This is acceptable for now, but it means `Game` still carries compatibility load that a backend-ready client would not need.

## What Is Already Good Enough For Backend Extraction

These are the strongest existing assets:

### 1. Session-oriented application layer

The project already has a meaningful application/service seam around authoritative session state.

That is the most important prerequisite for backend extraction.

### 2. Split presentation coordination

Turn flow, bot flow, and desktop presentation responsibilities are no longer collapsed into one class.

That makes it realistic to:

- move bot execution to a host process
- keep rendering on the client
- keep current desktop app working while doing it

### 3. Save/load and continuation groundwork

Local save/load is already much closer to backend-style snapshot restoration than before.

This reduces the amount of “special client-only restore logic” that would otherwise block backend work.

### 4. Explicit desktop assembly/runtime/session packages

The package structure now already hints at the future split:

- local client assembly/runtime
- session bridge/host seams
- presentation-only UI packages

That is a strong staging point for introducing actual backend/client package roots.

## Main Remaining Technical Blockers

These are the blockers that matter most before the project is truly backend-ready.

### Blocker A: the client session-update gateway — RESOLVED

The client now has three complementary transport-neutral seams:

- `ClientSessionUpdates` — listener-only snapshot stream (receive snapshots from host)
- `SessionCommandPort` — command submission and state query (send commands to host)
- `ClientSessionSnapshot` — now carries the full `SessionState` alongside session metadata

`ClientSessionSnapshot` was intentionally small initially (just `sessionId`, `version`, `status`,
`viewAvailable`). It now also carries the complete authoritative `SessionState`. This means:
- any host implementation (embedded or remote) can push a self-sufficient snapshot
- the client can reconstruct its local presentation model from the received snapshot
- a remote transport MVP can serialize and push `ClientSessionSnapshot` over the wire directly

`DesktopClientSessionModel.sessionState()` exposes the current session state for any client code
that needs to drive its presentation from host-approved state rather than polling live runtime objects.

Presentation-layer adapters (debt, auction, purchase, trade) depend on `SessionCommandPort`
instead of the full `SessionApplicationService`. `SessionApplicationService` implements
`SessionCommandPort`, so the embedded local mode works without any behavioral change.

The current seam should evolve cleanly into something that works for both:

- local embedded host (current: `SessionApplicationService` implements `SessionCommandPort`)
- remote backend host (future: transport adapter implements `SessionCommandPort`)

### Blocker B: bot ownership is only partially host-owned so far

Bot coordination is cleaner now, and embedded local mode already routes bot stepping through a host-owned loop.
The concrete bot collaborators live in `host.bot` (with `DesktopHostBotInteractionAdapter` as the only
desktop-specific implementation in `presentation.game.desktop.assembly`).

Additional narrowing steps now in place:

- `BotTurnScheduler` no longer imports `DesktopClientSettings`; `skipAnimations` is injected as `BooleanSupplier`
- `host.bot` no longer reaches directly into desktop runtime popup services, trade controllers, or turn-player-only projected view suppliers
- those dependencies now cross the boundary through a dedicated desktop interaction adapter
- `SessionBackedComputerTurnContext` now depends on `SessionCommandPort` instead of `SessionApplicationService` — all bot command submissions and state queries go through the transport-neutral interface
- `GameBotTurnHooksAdapter` also takes `SessionCommandPort` for the main command flow; the computer auction action (a specialized application-layer behavior) is injected as a `Function<String, CommandResult>` lambda rather than requiring the full service type

Backend-ready target:

- bot loop belongs to the host
- client only renders resulting snapshots

### Blocker C: legacy bridge still sits between app and runtime in the same process

Progress since last update:
- `OverlaySessionStateStore` introduced in `application.session`: encapsulates the five mutable
  flow-state fields (`pendingDecision`, `auctionState`, `activeDebt`, `tradeState`,
  `turnContinuationState`) that previously lived as instance variables on `SessionApplicationService`
- `SessionApplicationService.currentState()` is now a plain `overlay.get()` call — turn phase
  derivation and stale pending-decision clearing happen inside the store's `get()` method
- `SessionApplicationService` keeps its convenience `Supplier<SessionState>` constructor, so
  `LegacySessionApplicationFactory` and existing tests need no changes; the overlay store is
  created internally from the supplier



This is the biggest structural bridge still left.

Progress made:

- `EmbeddedDesktopSessionHost` now implements `SessionCommandPort`, making it the single named
  entry point for both command submission and snapshot reception
- `HostedLocalSession` explicitly extends `SessionCommandPort`, so any future host implementation
  must satisfy both halves of the client-facing seam
- presentation-layer adapters already depend on `SessionCommandPort` — they can be rewired to
  the host entry point without behavior change once the assembly is restructured
- `SessionPresentationStatePort` introduced for the legacy override-state operations
  (`hasAuctionOverride`, `hasTradeOverride`, `hasPendingDecisionOverride`, `clearActiveDebtOverride`,
  `restoreFrom`); shell coordinators, `GameSessionStateCoordinator`, and
  `RestoredSessionReattachmentCoordinator` no longer import `SessionApplicationService`
- `GameDesktopShellDependencies.StateAccess` now holds two narrow suppliers (`SessionCommandPort`
  and `SessionPresentationStatePort`) instead of one `SessionApplicationService` supplier
- `GamePresentationFactory.Dependencies` now holds `SessionCommandPort` + explicit
  `Consumer<TurnContinuationGateway>` + `Function<String,CommandResult>` instead of the full
  application service; `GamePresentationFactory` no longer imports `SessionApplicationService`
- `SessionApplicationService` removed from `Game`, `GameDesktopAssembly`, `GameDesktopHostContext`,
  and `GameSessionBridge` record — these now hold only narrow types: `SessionCommandPort`,
  `SessionPresentationStatePort`, `SessionPaymentPort`, `Consumer<TurnContinuationGateway>`,
  `Function<String,CommandResult>`, and `internalCommandPort` (a `SessionCommandPort` back-channel
  that `Game.submitCommand()` uses to avoid routing through the proxy)
- `SessionPaymentPort` introduced in `application.session` for the `handlePaymentRequest` seam;
  `Game` no longer imports `SessionApplicationService` at all
- `SessionApplicationService` is now only imported in: `GameSessionBridgeFactory` (where it is
  used internally to build the bridge) and `LegacySessionApplicationFactory` (where it is built)
- no assembly record above the bridge factory level references `SessionApplicationService` by name
- `ForwardingSessionCommandPort` introduced in `client.session`: a mutable proxy that holds a
  separate `stateSource` (always points to the local `SessionApplicationService` for projected state)
  and a `commandDelegate` (initially `SessionApplicationService`, rewired to
  `EmbeddedDesktopSessionHost` after session start via `setExternalCommandDelegate`)
- all five presentation-layer adapters (debt, auction, purchase, trade, trade controller) now
  receive the `ForwardingSessionCommandPort` proxy, not `SessionApplicationService` directly —
  their command submissions route through `EmbeddedDesktopSessionHost.handle()` transparently
- `EmbeddedDesktopSessionHost.handle()` now publishes a snapshot directly after each accepted
  command; `SessionApplicationService.postCommandListener` and `setPostCommandListener()` removed
- `DesktopHostedGame.setExternalCommandDelegate()` replaces `setPostCommandListener()` — the host
  calls this after game creation to wire the proxy delegate to itself
- `EmbeddedDesktopSessionHost` is now the single named command entry point for both embedded and
  future remote modes; swapping to a remote host only requires changing what the proxy delegates to

Remaining:

- session host output must become client-facing session state/view state
- legacy runtime reconstruction must become a client adapter

### Blocker D: server.transport HTTP MVP — DONE

`fi.monopoly.server.transport` now exists with:

- `SessionCommandMapper` — deserializes JSON (with `"type"` discriminator) to typed
  `SessionCommand` instances; all 22 command types covered; no Jackson annotations on domain types
- `SessionHttpServer` — built-in Java `HttpServer` exposing:
  - `POST /command` → `SessionCommandPort.handle()`, returns `{"accepted":…,"rejections":[…]}`
  - `GET /snapshot` → serialized `ClientSessionSnapshot` JSON
  - `GET /health` → `{"status":"ok"}`

The HTTP server is wired into `EmbeddedLocalDesktopClientBindingFactory` behind
`-Dmonopoly.http.port=<port>`. When that system property is set, the embedded session host is
exposed over HTTP on the given port; a JVM shutdown hook stops the server cleanly. When the
property is absent the app runs in normal embedded-only mode with no behavioral change.

`EmbeddedDesktopSessionHost.currentSnapshot()` is now public so the HTTP server (and other
transport implementations) can poll it directly.

The full transport layer MVP is now in place:

- `SessionCommandSerializer` — serializes `SessionCommand` → JSON (symmetric with `SessionCommandMapper`)
- `HttpSessionCommandPort` — `SessionCommandPort` that POSTs commands to `/command`
- `HttpClientSessionUpdates` — `ClientSessionUpdates` that connects to `/events` SSE stream,
  auto-reconnects on disconnect, and dispatches received snapshots to registered listeners

A remote desktop client can substitute `HttpSessionCommandPort` + `HttpClientSessionUpdates` for the
embedded host binding without any changes to the five presentation-layer adapters — they only
see `SessionCommandPort` and `ClientSessionUpdates`.

Progress since HTTP MVP:
- `PendingDecision.payload` is now typed as the sealed interface `DecisionPayload` (domain package)
  with `@JsonTypeInfo` / `@JsonSubTypes` so Jackson round-trips the type through HTTP without
  transport-layer MixIns; `PropertyPurchaseDecisionPayload implements DecisionPayload`
- `server.session` package created: `SessionServer` wraps `SessionHttpServer` lifecycle (start,
  stop, shutdown hook) and is used by `EmbeddedLocalDesktopClientBindingFactory`; `StartSessionServer`
  documents the future standalone `main()` entry point and the remaining gateway extraction work
- Java upgraded to 21; `SessionHttpServer` uses `Executors.newVirtualThreadPerTaskExecutor()`;
  SSE reader uses `Thread.ofVirtual()`; `SessionCommandSerializer` and
  `InteractiveTurnEffectExecutor` use Java 21 pattern-matching switch statements

Progress since last update (starting order + domain extraction complete):
- All six gateway implementations are now fully pure-domain: `DomainAuctionGateway`,
  `DomainPropertyPurchaseGateway`, `DomainTurnActionGateway`, `DomainTurnContinuationGateway`,
  `DomainDebtRemediationGateway`, `DomainTradeGateway`
- `StartSessionServer` is fully operational as a standalone server with no Processing dependency
- `PureDomainSessionFactory.initialGameState()` now determines seat/turn order by simulated dice
  roll (standard Finnish Monopoly starting-order rule); ties among highest-rollers are resolved by
  recursive re-roll of only the tied group; tested with 50-seed sweep and RepeatedTest coverage
- 542 tests green

## Recommended Immediate Architectural Focus

Blockers A, B (partially), C (partially), and D are fully resolved. The standalone server
(`StartSessionServer`) is operational. The main remaining work:

1. **Make desktop client render from snapshot** — `Game` and the legacy Processing runtime should
   become a pure client-side rendering projection that reads from received `SessionState` rather
   than computing authoritative values themselves. This is the biggest remaining structural bridge.

Progress since last update (sessions 7–8):
- `GameSidebarPresenter.SidebarState` fully migrated: no `Player` fields remain; player colors
  derive from `SeatState.tokenColorHex`, active player name/spot/computer-badge from `SessionState`
- `GameSidebarStateFactory` simplified: sidebar phase display derives from `SessionState.turn().phase()`;
  removed legacy boolean flags (`gameOver`, `popupVisible`, `endRoundVisible`, `rollDiceVisible`)
- `GameFrameCoordinator.FrameHooks` trimmed: removed `endRoundVisible()`, `rollDiceVisible()`,
  `turnPlayer()`, `debtDebtor()`, `focusPlayer(Player)`; all removed or replaced with
  `focusDebtDebtor()` (encapsulates Player lookup inside coordinator implementation)
- `GamePrimaryTurnControls` and `GameBotTurnControlCoordinator` no longer take `Player` parameters;
  replaced with `BooleanSupplier isComputerControlled` / `boolean isComputer` booleans
- MDC logging (`updateLogTurnContext`) now takes `String winnerName, String turnPlayerName` — no Player
- `GameSidebarPresenter.SidebarState.debtState` field replaced with pre-formatted `String debtText`;
  debt text formatting moved to `GameSidebarStateFactory`
- `GamePresentationSupport.updateDebtButtons` now takes `boolean debtActive` instead of `DebtState`
- `GameDesktopPresentationHost.currentTurnPlayerSupplier` changed to `Supplier<String> turnPlayerNameSupplier`;
  player name extraction happens at assembly boundary (`GameDesktopBootstrapFactory`)
- `GameSessionState.winnerName()` derived accessor added; `PresentationHost.updateLogTurnContext`
  no longer accesses `Player winner` directly
- `WinnerHooks.showVictoryPopup(Player)` changed to `showVictoryPopup(String winnerName)` — winner
  name is now extracted in `GameSessionStateCoordinator.declareWinner()` before the hook call
- `projectedRollDiceAvailableSupplier`/`projectedEndTurnAvailableSupplier` removed from
  `GameBotTurnHooksAdapter` and `SessionBackedComputerTurnContext`; debug log now reads
  `state.turn().canRoll()` / `canEndTurn()` directly from the authoritative snapshot
- Legacy desktop starting order now uses `StartingOrderDeterminer` (dice-roll-based), matching
  `PureDomainSessionFactory`; `GameRuntimeAssemblyFactory` applies this at game setup

More Player removal (session 8 continued):
- `LegacySessionProjector.winnerPlayerId()` now uses `Supplier<String>` instead of `Supplier<Player>`;
  `LegacySessionApplicationFactory` and `GameSessionBridgeFactory.Hooks` both changed to `winnerPlayerId()`
- `GameSessionState.winnerPlayerId()` field added; set from both `setWinner(Player)` and
  `restoreSessionState()` (from `restoredSessionState.winnerPlayerId()` directly)
- `DebtActionDispatcher` no longer takes `Supplier<Player> actorSupplier`; actor playerId comes
  from `DebtStateModel.debtorPlayerId()` directly — three-arg constructor, no Player import
- `PendingDecisionPopupAdapter` no longer takes `Function<String, Player> playerResolver`;
  actor validity checked via `pendingDecision.actorPlayerId() != null` — five-arg constructor, no Player
- `GameSessionBridgeFactory.Hooks.playerById()` removed entirely (was only used by PopupAdapter)

More Player removal (session 9):
- `AuctionViewAdapter` — `Players players` dependency removed; actor computer check and name now
  come from `SessionState.seats` (`SeatKind.BOT` / `displayName()`); leader name+color passed to
  `PopupService.showPropertyAuction` as `String leaderName, Color leaderColor`
- `PopupService.showPropertyAuction` signature changed from `Player currentLeader` to
  `String leaderName, Color leaderColor`; `PropertyAuctionPopup` stores `String + Color` instead of `Player`
- `PropertyAuctionResolver` legacy callsite updated to extract `leader.getName()` / `leader.getColor()`
  at the call site (Player still used in that legacy class)
- `GameSessionBridgeFactory.Hooks.currentTurnPlayer()/players()` — removed from Hooks interface;
  `players::getPlayers` passed directly to `LegacyTradeGateway` and `TradeController` (Players is
  already a `create()` parameter); `currentTurnPlayer` derived internally via SessionState
  `turn().activePlayerId()` + Players lookup (`currentTurnPlayerSupplier` private helper)
- `RestoredSessionReattachmentCoordinator.restoreAuthoritativeState` — `Function<String, Player>
  playerById` parameter removed; `RestoredGameState` now carries `(boolean paused, boolean gameOver)`
  only — no `Player winner`; `winnerPlayerId` is read directly from `SessionState` by callers

More Player removal (session 9, continued):
- `GameRuntimeAssemblyFactory.Hooks.declareWinner(Player)` changed to
  `declareWinner(String winnerPlayerId)`; `DebtController.declareWinner` changed from
  `Consumer<Player>` to `Consumer<String>`; the Player is looked up in the hook
  implementation from `dependencies.playerById` before calling the existing coordinator method
- `WinnerHooks.focusWinner(Player)` changed to `focusWinner(String winnerPlayerId)`;
  `GameSessionStateCoordinator.declareWinner` passes `sessionState.winnerPlayerId()` to the hook;
  hook implementation in `GameDesktopPresentationCoordinator` looks up Player locally for the
  visual focus effect
- `RestoredSessionReattachmentCoordinator.Hooks.playerById` removed entirely; `DebtController`
  gains `restoreDebtStateFromModel(DebtStateModel, Runnable)` that resolves debtor/creditor Players
  internally using its own Players reference — callers no longer need a playerById function

More Player removal (session 10):
- `GamePresentationFactory.Hooks.currentTurnPlayer()` replaced with `boolean isCurrentPlayerComputerControlled()` — the only caller needed the boolean, not the Player object
- `GameSessionStateCoordinator.declareWinner(GameSessionState, Player, WinnerHooks)` replaced with `declareWinner(GameSessionState, String winnerPlayerId, String winnerName, WinnerHooks)` — winner name resolved from `SessionState.seats.displayName()` at the hook boundary
- `GameSessionState.winner` (Player field), `setWinner(Player)`, and `setWinnerPlayerId(String)` all removed; winner identity now stored as `winnerPlayerId` + `winnerName` String fields via `setWinnerInfo(String, String)`; `restoreSessionState()` now also restores `winnerName` from seats so loaded completed games display the winner name correctly
- `GameDesktopPresentationCoordinator.declareWinner(GameDesktopShellDependencies, Player)` replaced with `declareWinner(GameDesktopShellDependencies, String, String)` — winner name resolved via private `resolveWinnerName()` helper that reads `SessionState.seats` 
- `Game.java` dead private method `declareWinner(Player)` removed

More Player removal (session 11):
- `LegacyTurnActionGatewayAdapter`: `Supplier<Player> turnPlayerSupplier` → `BooleanSupplier hasActiveTurn` — `endTurn()` checks `getTurn() != null` without exposing Player to the gateway interface
- `GameDesktopShellDependencies.StateAccess`: `playerByIdResolver` field removed; `ActionAccess.focusPlayerAction` (Consumer<Player>) → `focusPlayerByIdAction` (Consumer<String>)
- `GameDesktopShellDependencies`: `playerById(String)` method removed; `focusPlayer(Player)` → `focusPlayerById(String playerId)`
- `GameDesktopPresentationCoordinator.focusWinner(String)`: no longer looks up Player by ID; directly calls `dependencies.focusPlayerById(winnerPlayerId)`
- `GameDesktopPresentationCoordinator.focusDebtDebtor()`: uses `"player-" + debt.paymentRequest().debtor().getId()` to get ID, then `focusPlayerById()` instead of `focusPlayer(Player)`
- `GameDesktopHostFactory.Hooks`: `playerByIdResolver()` method removed; `focusPlayerAction(Consumer<Player>)` → `focusPlayerByIdAction(Consumer<String>)`
- `Game.java`: `playerById(String)` method removed; new `focusPlayerById(String)` private method does the player lookup, coord reset, and `players.focusPlayer()` call internally

More Player removal (session 12):
- `RestoredLegacySessionRuntime`: `playersById` map field removed — players can be found via `Players.getPlayers()` stream lookup by numeric ID; `LegacySessionRuntimeRestorerTest` and `SessionPersistenceServiceTest` updated with local `playerById()` helper
- `GameDesktopShellDependencies.StateAccess`: `Supplier<Player> currentTurnPlayerSupplier` replaced with `BooleanSupplier hasActiveTurnSupplier` + `BooleanSupplier isComputerTurnSupplier`; `GameDesktopShellDependencies.currentTurnPlayer()` replaced with `hasActiveTurn()` + `isComputerTurn()`
- `GameDesktopHostFactory.Hooks`: `currentTurnPlayerSupplier()` removed; `hasActiveTurnSupplier()` + `isComputerTurnSupplier()` + `turnPlayerNameSupplier()` added; `Game.java` wires three lambdas from `players.getTurn()` instead of passing `this::currentTurnPlayer`
- `GameDesktopPresentationCoordinator.hasActivePlayer()` / `isCurrentPlayerComputer()` — now call `deps.hasActiveTurn()` / `deps.isComputerTurn()` (no longer access Player objects)
- `GameDesktopBootstrapFactory`: `hooks.turnPlayerNameSupplier()` used directly instead of building a Player-unwrapping lambda from `hooks.currentTurnPlayerSupplier()`
- `HostBotInteractionAdapter.acceptActivePopupFor(Player)` / `declineActivePopupFor(Player)` — renamed to `acceptActivePopup()` / `declineActivePopup()` (Player param was unused in the desktop implementation)

More Player removal (session 13):
- `GameBotTurnDriver.Hooks.currentTurnPlayer()` removed — `resolveActingPlayer()` now uses `findPlayerById(sessionState.turn().activePlayerId())` directly; `handleAuctionStep()` does the same instead of calling the removed hook
- `GameBotTurnDriver.Hooks.resolveVisiblePopupFor(Player)` → `resolveVisiblePopupFor(ComputerPlayerProfile)` — aligns the driver hooks signature with the already-updated `HostBotInteractionAdapter`
- `GameBotTurnHooksAdapter.turnPlayerSupplier` field removed — no longer needed since `currentTurnPlayer()` hook is gone; the Player-fetching lambda in `GamePresentationFactory` that provided this supplier was also removed
- `HostBotInteractionAdapter.currentGameView(Player)` / `currentPlayerView(Player)` → `currentGameView(String playerId)` / `currentPlayerView(String playerId)` — Player removed from view factory signatures throughout the chain; `GameDesktopHostFactory.Hooks`, `GameDesktopShellDependencies.ProjectionAccess`, `GamePresentationFactory.Hooks`, and `DesktopHostBotInteractionAdapter` all updated; `Game.java` adds `createGameViewById(String)` / `createPlayerViewById(String)` helpers with internal player stream lookup; `SessionBackedComputerTurnContext` calls with `"player-" + player.getId()`; `Player` import removed from `GameDesktopHostFactory` and `GameDesktopShellDependencies`

More Player removal (session 16):
- `GameBotTurnDriver.Hooks.findPlayerById(String) → Player` removed entirely; replaced with two targeted hooks: `isComputerPlayer(String)` + `computerProfileFor(String)` — bot driver no longer needs a Player object at any step
- `GameBotTurnDriver.Hooks.createTurnContext(Player)` → `createTurnContext(String playerId, ComputerPlayerProfile)` — `SessionBackedComputerTurnContext` now takes `playerId` string; the one remaining `player.getName()` log reference fixed to `playerId`
- `GameBotTurnDriver.Hooks.handleComputerTradeTurn(Player)` → `handleComputerTradeTurn(String actorId, ComputerPlayerProfile)` — Player no longer passed across the bot driver boundary
- `HostBotInteractionAdapter.handleComputerTradeTurn(Player)` / `tryInitiateComputerTrade(Player)` → both now take `String proposerId/actorId, ComputerPlayerProfile` — Player removed from the host/bot interaction adapter interface; `DesktopHostBotInteractionAdapter` updated accordingly
- `TradeController.handleComputerTradeTurn` and `tryInitiateComputerTrade` now accept `String actorId, ComputerPlayerProfile` publicly; internal `findPlayerByDomainId(String)` helper added for the parts that still need a legacy Player (StrongTradePlanner, openTrade)
- `TradeController.resolveTradeProfile(Player)` / `resolveStrongTradeConfig(Player)` → now take `ComputerPlayerProfile` directly — Player access removed from these private helpers
- `BotSessionQueries` interface: `isComputerPlayer(String)` + `computerProfileFor(String)` methods; `GameSessionQueries` implements both via internal `findPlayerById` stream helper
- `GameBotTurnDriverTest.HooksStub` updated to implement the new interface: `contextPlayer` (Player) → `contextPlayerId` (String); `findPlayerById` / `createTurnContext(Player)` / `handleComputerTradeTurn(Player)` replaced with new signatures

More Player removal (session 17):
- `GameSessionQueries.calculateBoardDangerScore(Player)` → `calculateBoardDangerScore(String playerId)` — Player lookup moved inside the method; `SessionViewFacade.boardDangerScoreSupplier` changed from `Function<Player,Integer>` to `Function<String,Integer>`; `GameDesktopPresentationCoordinator` lambda updated accordingly
- `SessionViewFacade.createGameView(Player)` / `createPlayerView(Player)` — renamed to `buildGameView(Player)` / `buildPlayerView(Player)` (private); public API now `createGameView(String playerId)` / `createPlayerView(String playerId)` with internal player lookup via `findPlayerByDomainId()`; `GameDesktopPresentationHost` and `Game.java` updated to use String-based API, removing the player-lookup helpers that used to live in `Game.createGameViewById`/`createPlayerViewById`

## Remote Mode Player Setup and Bot Difficulty (sessions 18–19)

- `RemotePlayerSetupView` (`presentation.remote`) — pre-game setup screen shared by both remote and local modes:
  player count (2–4), per-slot name text field with keyboard input, Human/Bot toggle, difficulty toggle (Helppo/Norm. for bot slots), color swatch (6 presets, cycles on click for human slots)
- `KeyboardInteractiveView` (`presentation.remote`) — optional interface; `MonopolyApp.keyPressed()` routes to current view if it implements it
- `BotDifficulty` enum (`domain.session`): `NORMAL` (greedy) and `EASY` (40% skip purchases, 50% pass auctions)
- `PureDomainBotDriver`: carries `Map<String, BotDifficulty> difficulties`; EASY mode behavior via `ThreadLocalRandom`; bug fixed: old SELL_BUILDING branch returned unconditionally (silently skipping MORTGAGE_PROPERTY when no buildings found)
- `SessionRegistry` + `EmbeddedSessionServer`: new 4-arg `create(names, colors, seatKinds, difficulties)` overloads; `buildDifficultyMap()` maps seat index → player ID → difficulty
- `HttpBackedDesktopClientBindingFactory` refactored: shows setup view first via `SwitchableViewPort`;
  `launchSession()` creates `EmbeddedSessionServer` + session with chosen config (including difficulties) on "Aloita peli" click
- `EmbeddedLocalDesktopClientBindingFactory` also shows `RemotePlayerSetupView` first;
  `DeferredLocalSessionControls` blocks `startFreshSession()` until user clicks "Aloita peli";
  on start, applies `monopoly.players` + `monopoly.profiles` system properties and starts the session
- `UiTokens` setup screen constants added (`setupCardWidth`, `setupSlotHeight`, `setupNameFieldWidth`, etc.)
- `RemoteSessionBoardView` improvements (session 18–19):
  - Sidebar management and debt buttons use localized property names via `propertyDisplayName(SpotType.getStringProperty("name"))`
  - Build buttons restricted to complete color groups (`completedColorGroups()`); only show properties buildable per even-building rule (`minHouseCountPerGroup()`); house count shown in label
  - Mortgage/redeem list capped at 6; auction info label uses `propertyDisplayName()`
  - Auction display shows leading player name (`AuctionState.leadingPlayerId()`)
  - Debt display shows creditor name ("Pankki" or player name) and cash on separate lines
  - Player cards: cash aligned with name row, property count (N k.) aligned with spot row on right
- `Game.setupDefaultGameState()`: reads `monopoly.colors` system property (hex codes) set by `EmbeddedLocalDesktopClientBindingFactory.applyLocalConfig()`; falls back to `DEFAULT_PLAYER_COLORS` for missing/invalid entries
- `EmbeddedLocalDesktopClientBindingFactory.applyLocalConfig()`: maps `BotDifficulty.EASY → "SMOKE_TEST"` (NORMAL → "STRONG") so the difficulty toggle has real effect in local mode
- `PureDomainBotDriverTest`: 3 unit tests — debt fallthrough fix verification, NORMAL mode buys, EASY mode statistical decline test
- `PureDomainBotDriver.tryBuildGreedy()`: NORMAL bot now builds houses during `WAITING_FOR_END_TURN` — finds complete unmortgaged color groups, picks lowest-house-count property (even-building rule), dispatches `BuyBuildingRoundCommand` if cash ≥ housePrice; EASY bots always skip building and end turn directly; 2 new tests added (builds when group complete, ends when cash insufficient)
- `PureDomainBotDriver.tryUnmortgageGreedy()`: NORMAL bot unmortgages before building — picks cheapest mortgaged property in a group the bot fully owns, dispatches `ToggleMortgageCommand` if `cash - unmortgageCost ≥ 200€ (MIN_CASH_RESERVE)`; runs before building so groups can be freed for construction; 2 new tests added (total bot driver tests: 7)
- Bug fix: `handleDebt()` SELL_BUILDING branch used `findFirst()` ignoring even-selling rule — fixed to pick the eligible property with most buildings via `evenSellEligible()` / `buildingLevel()` helpers (mirrors `DomainDebtRemediationGateway.canSellBuildings` logic)
- `PureDomainGameSimulationTest`: `handleEndTurn` now tries building greedily (exercises building+hotel+debt-with-buildings paths); `handleDebt` checks `allowedActions` and uses even-selling-rule-aware property selection; `MAX_STEPS` raised to 4000; all 7 simulation tests pass

## HTTP Integration Tests for Build/Mortgage Commands (session 22)

- `SessionRegistryHttpIntegrationTest`: 3 new tests added (14 total):
  - `toggleMortgageCommandRejectedForUnownedProperty` — sends `ToggleMortgage` over HTTP; verifies no 400/500 and `"accepted":false` when player has no properties
  - `buyBuildingRoundCommandRejectedForUnownedProperty` — sends `BuyBuildingRound` over HTTP; same verification
  - `unknownCommandTypeReturns400` — sends unrecognized `"type"` value; verifies HTTP 400
  These confirm `SessionCommandMapper` deserializes both commands correctly and the domain rejects them gracefully without server errors. 570 tests green.

Remaining `Player` dependencies in presentation/host layer (deferred — Low priority):
- `TradeController.openTradeMenu()` and `openTrade(Player, Player)` — UI partner-selection popup and `StrongTradePlanner.plan(Player, List<Player>)` still use legacy Player objects internally; the public bot API is now clean
- `SessionViewFacade` internal implementation — Player objects still used inside `buildGameView`, `buildPlayerView`, `createPropertyView`, `estimateRent`, `estimateOfferedPropertyRent`; these are now behind private methods not exposed outside the facade
- `LegacyTradeGateway` — bridge between domain TradeOfferState and legacy TradeOffer; necessarily Player-tied as long as TradeOffer wraps Player objects

## Relationship To Older Plan Docs

The older PR1-PR12 docs are still useful as design history and scope references.

But they should now be read as:

- original migration intent
- subsystem design notes
- not the exact current implementation status

For current truth, prefer:

1. this document
2. `architecture-overview-diagrams.md`
3. the new backend-fast-track plan
