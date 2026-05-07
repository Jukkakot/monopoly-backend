# Networking MVP Plan

## Goal

Build online play as a split system:

- backend owns the authoritative game state
- multiple clients connect to one game session
- players can play against humans or computer players
- game state can be saved and resumed later

This document is intentionally MVP-first. The goal is not the final perfect architecture, but the smallest structure that can support multiplayer without rewriting game rules twice.

## MVP Scope

### In

- one backend process hosting many game sessions
- one client connected per player seat
- backend-authoritative turns and rules
- reconnect to an existing session
- save and resume a game
- support human and computer players in the same session

### Out

- matchmaking
- public lobbies
- spectator mode
- cross-version compatibility
- anti-cheat beyond authoritative backend validation
- full replay/event sourcing

## Recommended Split

### Backend

Responsibilities:

- create and manage game sessions
- validate player actions
- own turn order, dice results, payments, debt resolution, cards, jail state, bankruptcy, and winner detection
- generate and persist snapshots
- run computer-player decisions
- broadcast state updates to clients

### Frontend Client

Responsibilities:

- render board and UI
- send player intents to backend
- show current server-approved state
- animate state transitions locally
- handle reconnect and session join flow

Rule of thumb:

- client proposes
- backend decides

## MVP Architecture

### Backend shape

Use a separate Java backend service instead of embedding networking into the Processing app.

Recommended modules:

- `monopoly-domain`
  - pure game rules and state transitions
- `monopoly-server`
  - HTTP/WebSocket API, session lifecycle, persistence, bots
- current Processing app becomes `monopoly-client`
  - renderer + local input + networking client

Why this split:

- domain logic stays reusable for bots and tests
- client can later be replaced without moving rules again
- backend tests can run headless without Processing

### Runtime model

Per running session:

- one `GameSession`
- one authoritative `GameState`
- one list of seats
- each seat is `HUMAN` or `BOT`
- one session event loop / serialized command queue

Important MVP constraint:

- process one command at a time per session

That removes most race conditions early and is enough for turn-based Monopoly.

## Network Model

### Transport

Recommended MVP transport:

- HTTP for lobby/session setup and save/load actions
- WebSocket for live game updates and player actions

Reason:

- HTTP is simple for create/join/load
- WebSocket is better than polling for turn-based state pushes and reconnect sync

### Client -> server commands

Examples:

- `create_session`
- `join_session`
- `start_game`
- `roll_dice`
- `end_turn`
- `buy_property`
- `decline_property`
- `retry_debt_payment`
- `declare_bankruptcy`
- `sell_house`
- `buy_house`
- `mortgage_property`
- `unmortgage_property`
- `choose_popup_option`
- `change_language`

Each command should include:

- `sessionId`
- `playerId`
- `clientCommandId`
- command payload

### Server -> client events

Examples:

- `session_created`
- `player_joined`
- `seat_updated`
- `game_started`
- `state_snapshot`
- `turn_changed`
- `popup_requested`
- `animation_hint`
- `game_saved`
- `error`

For MVP, sending a full normalized state snapshot after each accepted command is acceptable.

Later optimization:

- move to snapshot + small diffs

## Authoritative State Model

The backend state should not reuse UI classes directly. Create transport/domain-safe models such as:

- `GameSessionState`
- `BoardState`
- `TurnState`
- `PlayerState`
- `PropertyState`
- `DebtState`
- `PopupState`
- `DiceState`

The Processing client should map server state into render state.

Do not let backend depend on:

- ControlP5
- Processing rendering types
- popup widgets
- image classes

## Computer Player Plan

Computer players should run only on backend.

### MVP bot levels

- `EASY`
  - buys randomly with simple cash threshold
  - rarely builds
  - mortgages late
- `NORMAL`
  - buys by color-group value and remaining cash
  - builds when monopoly exists and reserves cash
  - resolves debt with simple heuristics
- `HARD`
  - better liquidation ordering
  - evaluates rent risk and monopoly blocking
  - smarter unmortgage/build timing

### MVP bot rules

- bots act only when it is their turn
- backend schedules bot action after a small delay
- bot logic reads the same authoritative state as humans

## Persistence Plan

### Recommended database

Use PostgreSQL.

Why PostgreSQL:

- strong transactional model for save/resume correctness
- easy IntelliJ integration
- good fit for relational game/session data
- `jsonb` is available for flexible snapshot payloads
- easy local setup with Docker

### Local dev setup recommendation

Use Docker Compose with:

- `postgres`
- optional `adminer` or `pgadmin`

Recommended local connection:

- host: `localhost`
- port: `5432`
- database: `monopoly`
- schema: `public`

This is easy to connect to from IntelliJ Database Tools and easy to access from shell tools like `psql` if installed.

## Database Strategy

Use both:

- relational tables for stable queryable metadata
- `jsonb` snapshot for complete restorable game state

This is the fastest MVP path.

Do not start with full event sourcing.

### Core tables

#### `game_session`

- `id` UUID PK
- `status` text
- `created_at` timestamptz
- `updated_at` timestamptz
- `started_at` timestamptz nullable
- `finished_at` timestamptz nullable
- `version` bigint
- `active_snapshot_id` UUID nullable
- `host_player_id` UUID nullable

#### `session_seat`

- `id` UUID PK
- `session_id` UUID FK
- `seat_index` int
- `seat_type` text
- `player_id` UUID nullable
- `bot_profile` text nullable
- `display_name` text
- `color_key` text
- `connected` boolean

#### `player_account`

- `id` UUID PK
- `username` text unique
- `created_at` timestamptz

MVP note:

- this can be optional at first if you want join codes instead of full accounts

#### `game_snapshot`

- `id` UUID PK
- `session_id` UUID FK
- `version` bigint
- `created_at` timestamptz
- `snapshot_json` jsonb

This is the main save/resume payload.

#### `session_command_log`

- `id` UUID PK
- `session_id` UUID FK
- `version` bigint
- `player_id` UUID nullable
- `command_type` text
- `command_json` jsonb
- `created_at` timestamptz

Useful for debugging even in MVP.

### Snapshot JSON contents

Store enough to reconstruct the full match:

- session metadata
- current turn phase
- players
- player cash
- player jail rounds / jail cards
- player owned properties
- mortgages
- houses and hotels
- board positions
- decks and remaining card order
- popup/debt state if session is mid-decision
- dice/pending action state
- eliminated players

### Why snapshot-first is the right MVP

- easiest way to resume games reliably
- avoids mapping every tiny rule object to many tables on day one
- still allows queryable metadata in relational columns

Later, if needed, add derived relational tables for analytics.

## Save / Resume Flow

### Save

1. lock session command processing
2. serialize authoritative state
3. write `game_snapshot`
4. update `game_session.active_snapshot_id` and `version`
5. release session

### Resume

1. load `game_session`
2. load `active_snapshot`
3. rebuild in-memory session state
4. reconnect players to seats
5. push a full `state_snapshot` to all connected clients

## Suggested MVP Delivery Phases

### Phase 1

- extract pure domain state and commands from UI-heavy classes
- define server-safe `GameState` and command handlers

### Phase 2

- create backend service skeleton
- create local session manager
- support single session, one human client, rest bots

### Phase 3

- add WebSocket state sync
- add create/join session flow
- support two human players

### Phase 4

- add PostgreSQL persistence with snapshot save/load
- reconnect and resume support

### Phase 5

- add bot difficulty profiles
- improve command validation and error handling

## Main Risks

### Risk 1

Current game logic is mixed with UI and runtime singletons.

Mitigation:

- isolate pure state transitions before networking

### Risk 2

Client animations may currently assume local authority.

Mitigation:

- server sends approved resulting state
- client animates from previous accepted state to next accepted state

### Risk 3

Mid-popup / mid-debt states are easy to lose on save/resume.

Mitigation:

- treat popup/debt choice as explicit authoritative state, not just temporary UI

## Concrete Recommendation

If starting implementation next, the best MVP path is:

1. extract a backend-safe domain module from current game logic
2. build a Java backend with HTTP + WebSocket
3. use PostgreSQL in Docker for persistence
4. persist full session snapshots in `jsonb`, plus relational session metadata
5. run computer players only on backend

## References

- PostgreSQL transactions: https://www.postgresql.org/docs/current/tutorial-transactions.html
- PostgreSQL JSON types: https://www.postgresql.org/docs/current/static/datatype-json.html
- PostgreSQL JSON functions/operators: https://www.postgresql.org/docs/current/functions-json.html
- IntelliJ PostgreSQL connection docs: https://www.jetbrains.com/help/idea/postgresql.html
- IntelliJ run DB in Docker: https://www.jetbrains.com/help/idea/running-a-dbms-image.html
