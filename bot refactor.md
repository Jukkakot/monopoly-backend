# Bot Refactor — Contracts & Interfaces (FROZEN)

**Status:** Design proposal — freeze these before writing implementation code.
**Audience:** the implementing agent/developer. These are the seams that make the utility-bot refactor *and* the evaluation harness possible without rework or hidden-state bugs. Read alongside `bot-utility-architecture.md` (the research report) and `bot-evaluation-harness.md`.

> **Grounding note.** Type names below are taken from the real codebase where known (commands, `SessionState`, the `pendingDecision` phase enum). Where a signature is illustrative, it's marked _(verify in code)_. The implementing agent MUST confirm exact field/accessor names against the domain types before coding, and update this doc if reality differs.

---

## 0. The one principle

**Decisions are values, produced by a pure function, executed separately.**
- The bot never mutates game state directly and never issues commands from inside its decision logic.
- A **Planner** turns `(state, memory, params, rng)` into an **Intent** (a value).
- An **Executor** turns an `Intent` into one or more domain **Command**s and applies them.
- All cross-turn knowledge lives in an explicit **BotMemory** value passed in/out — never in hidden mutable fields on the driver.

This is what makes the loop class of bug structurally impossible and lets the same Planner run in production (Executor → `publisher.handle`) and in the headless harness (Executor → engine).

---

## 1. Decision-point model (already exists — reuse it)

The engine already exposes what decision is required via `SessionState.pendingDecision` (a phase enum) plus `resolveActorId(state)`. The bot's current `dispatchGreedy` switches on exactly these. **Do not invent a new turn model — wrap this one.**

Phases the bot must answer for _(confirmed in `PureDomainBotDriver.dispatchGreedy`)_:

| Phase | Who acts | Decision the Planner must make |
|---|---|---|
| `WAITING_FOR_ROLL` | active player | roll (trivial) |
| `WAITING_FOR_CARD_ACK` | active player | acknowledge card (trivial) |
| `WAITING_FOR_END_TURN` | active player | turn management: build / mortgage / propose trade(s) / then end turn |
| `WAITING_FOR_DECISION` | `resolveActorId` | buy-or-auction a property, OR respond to a received trade, OR jail choice |
| `RESOLVING_DEBT` | indebted player | raise cash: mortgage / sell buildings / pay / declare bankruptcy |
| `WAITING_FOR_AUCTION` | bidder | bid amount or pass |

The harness control flow is therefore:
```
while (!terminal(state)) {
    actor = resolveActorId(state);              // engine logic, shared with prod
    Intent intent = strategy.decide(state, memory, params, rng);
    state = executor.apply(intent, state);      // → Command(s) → engine
    memory = memory.afterApplied(intent, state);// explicit, no hidden fields
}
```

---

## 2. The Intent algebra (FREEZE THIS — must be complete)

An `Intent` is a sealed hierarchy. **It must cover every decision the bot can face.** High-level where it matters (trades), low-level where the engine is already atomic (roll). The Executor expands high-level intents into the command protocol — that is the whole point.

```java
public sealed interface Intent permits
    Roll, AcknowledgeCard, EndTurn,
    BuyProperty, DeclineProperty,                 // property landing
    BuildHouses, SellHouses, SetMortgage,         // development / liquidity
    ProposeTrade, RespondToTrade,                 // trades (HIGH-LEVEL)
    Bid, PassAuction,                             // auctions
    ResolveDebt, DeclareBankruptcy,               // debt
    JailChoice,                                   // jail
    NoOp {}
```

Key high-level intents:

```java
// A whole trade proposal as ONE strategic decision. Executor expands to
// OpenTradeCommand → EditTradeOfferCommand* → SubmitTradeOfferCommand.
record ProposeTrade(TradeProposal proposal) implements Intent {}

// Response to a received offer. COUNTER carries the full counter-proposal.
record RespondToTrade(Response kind, TradeProposal counterOffer /*nullable*/) implements Intent {}
enum Response { ACCEPT, DECLINE, COUNTER }

// Debt resolution as ONE intent; Executor sequences mortgage/sell/pay commands.
record ResolveDebt(List<DebtAction> ordered) implements Intent {}

record Bid(long amount) implements Intent {}
record JailChoice(JailAction action) implements Intent {} // PAY_FINE | USE_CARD | STAY
```

**Completeness rule:** every `case` currently in `dispatchGreedy`, `handleDecision`, `handleDebt`, `handleAuction`, `handleTradeDecision`, `handleTradeEditing`, and `tryInitiateStrategicTrade` MUST map to exactly one Intent. The implementing agent's first job is to produce the full §3 table and prove no decision is left out.

---

## 3. Intent ↔ Command mapping (audit table — fill & verify first)

The engine's real commands (from `find -name *Command.java`). The bot currently issues the 21 marked ●.

| Intent | Command(s) the Executor issues | Notes |
|---|---|---|
| `Roll` | `RollDiceCommand` ● | |
| `AcknowledgeCard` | `AcknowledgeCardCommand` ● | |
| `EndTurn` | `EndTurnCommand` ● | issued after management intents |
| `BuyProperty` | `BuyPropertyCommand` ● | |
| `DeclineProperty` | `DeclinePropertyCommand` ● | → triggers auction |
| `BuildHouses` | `BuyBuildingRoundCommand` ● | one per round; Executor loops to target houses |
| `SellHouses` | `SellBuildingRoundCommand` / `...AcrossSetForDebt` | verify which the bot uses |
| `SetMortgage` | `ToggleMortgageCommand` ● / `MortgagePropertyForDebtCommand` ● | mortgage vs unmortgage |
| `ProposeTrade` | `OpenTradeCommand` ● + `EditTradeOfferCommand` ● (×N) + `SubmitTradeOfferCommand` ● | **multi-command protocol owned by Executor** |
| `RespondToTrade(ACCEPT)` | `AcceptTradeCommand` ● | |
| `RespondToTrade(DECLINE)` | `DeclineTradeCommand` ● | **record outcome in ledger here** |
| `RespondToTrade(COUNTER)` | `CounterTradeCommand` ● + edits + submit | |
| (cancel own trade) | `CancelTradeCommand` ● | model as `RespondToTrade` on own editing, or a dedicated intent |
| `Bid` | `PlaceAuctionBidCommand` ● | |
| `PassAuction` | `PassAuctionCommand` ● | + `FinishAuctionResolutionCommand` ● where needed |
| `ResolveDebt` | `PayDebtCommand` ● / `MortgagePropertyForDebtCommand` ● / `SellBuildingForDebtCommand` ● | ordered list |
| `DeclareBankruptcy` | `DeclareBankruptcyCommand` ● | |
| `JailChoice` | `PayJailFineCommand` ● / `UseGetOutOfJailCardCommand` ● / (roll) | |

Commands NOT bot-driven (host/system/human): `AbortGameCommand`, `LeaveGameCommand`, `RefreshSessionViewCommand`, `SellBuildingRoundsAcrossSetForDebtCommand` (verify), `FinishAuctionResolutionCommand` (verify if system).

---

## 4. `BotStrategy` — the comparison seam

```java
public interface BotStrategy {
    String name();   // versioned id, e.g. "pure-domain-v1", "utility-v3"
    Intent decide(SessionState state, BotMemory memory, BotParams params, RandomSource rng);
}
```

- **Pure-ish:** no game mutation, no `publisher.handle`, no I/O, no clock, no `Math.random`. All randomness via `rng`.
- **Stateless or per-game instanced:** no shared mutable statics → harness can run thousands of games in parallel.
- Two implementations: `PureDomainStrategy` (wraps extracted old logic) and `UtilityStrategy` (new planner).

---

## 5. `BotMemory` + `ProposalLedger` (kills the loop class of bug)

Explicit, immutable-by-convention value, **one per (game, bot)**, created at game start, passed into every `decide`, updated by the runner — never stored as fields on a shared driver.

```java
record BotMemory(ProposalLedger ledger, /* future: opponent models, threat memory */ ...) {}
```

`ProposalLedger` is the single authority for "have I already tried this and how did it go?" — it replaces `tradeDeclinesByPartnerId`, `lastDeclinedOfferAmount`, `declinedSwapTargets`, `counterEditAttempts`, etc.

```java
final class ProposalLedger {
    enum Outcome { PROPOSED, DECLINED, ACCEPTED, COUNTERED }
    void record(TradeProposalId id, Outcome o);   // called at the bot's OWN decision point
    Outcome outcomeOf(TradeProposalId id);        // null if never seen
    boolean isFailed(TradeProposalId id);         // DECLINED/COUNTERED and not improved
}
```

**Invariant that makes loops impossible:** the Planner's candidate generation ends with
`candidates.removeIf(p -> ledger.isFailed(p.id()) && !p.strictlyImproves(prior))`.
Because the ledger is monotonic and updated at decision points (not inferred from snapshots), no fast re-open or missed transition can resurrect a failed proposal.

🔶 **ASK HAUKI:** should `isFailed` be permanent (matches current per-game design, simplest) or decay/reset when the board materially changes (allows a good late-game re-try, more code)? Default in this doc: permanent per game, but `strictlyImproves` lets a better offer through.

---

## 6. `TradeProposal` — first-class value with identity

```java
record TradeProposal(String partnerId, Side give, Side get) {
    record Side(Set<String> propertyIds, long cash) {}
    TradeProposalId id();           // canonical: (partner, give-set, get-set), cash bucketed
    boolean strictlyImproves(TradeProposal prior); // same swap, better cash for the partner
}
```

- Identity ignores trivial cash jitter (bucket cash) so "same deal, $1 more" doesn't count as new — tune bucket size.
- Every offer the bot considers (propose / counter / accept-eval) is one of these, scored by the same utility function applied to the *resulting* state.

---

## 7. Utility model contracts (the new bot's internals)

```java
// A single normalized scoring axis. Pure.
interface Consideration {
    String id();
    double score(DecisionContext ctx);   // returns [0,1] (raw input × response curve applied inside)
}

// DecisionContext bundles the candidate action + state + memory + params + precomputed valuations.
record DecisionContext(SessionState state, String botId, BotMemory memory,
                       BotParams params, CandidateAction action, Valuations val) {}
```

**Combination (fixed):** multiplicative with Dave Mark's compensation factor (see research report §A). Veto considerations (affordability, bankruptcy-safety) score 0 to disqualify. Per-action category weight (normal/important/emergency) ×, then phase multiplier ×, then personality multiplier ×.

**Response curves as data:**
```java
record Curve(Type type, double[] params) { enum Type { LINEAR, POLYNOMIAL, LOGISTIC, STEP } double eval(double x); }
```

**Selection:** argmax for "best play"; softmax/top-N weighted-random with per-agent temperature for human-like variety. Momentum bonus (×1.1–1.25 on last action) to avoid dithering.

---

## 8. `BotParams` — externalized, versioned config

```java
record BotParams(
    String id,                       // archetype/version, e.g. "slumlord", part of strategy name
    Map<String,Double> weights,      // per-consideration & per-action weights
    Map<String,Curve> curves,        // response curves by id
    Personality personality,         // aggression, riskTolerance, monopolyAppetite,
                                     // liquidityPreference, tradeWillingness, targeting (each [0,1])
    PhaseWeights phaseWeights        // multipliers per (phase × action/consideration)
) {}
```

- Loaded from JSON, validated on load, **content-hashed** (`configHash`) for report reproducibility.
- `Personality` sampled once per bot at game start from an archetype + jitter, via the game `RandomSource` (so it's reproducible).
- 🔶 **ASK HAUKI:** ship N fixed archetypes (e.g. Slumlord / High-Roller / Trader / Hoarder / Balanced) or sample freely within ranges? Default: archetypes + jitter (more controllable, distinct-but-competent).

---

## 9. `RandomSource` — determinism seam (cross-cutting)

```java
interface RandomSource { int nextInt(int bound); double nextDouble(); long nextLong();
                         RandomSource derive(String salt); }
```

- **One seeded source per game**, threaded through: dice, card decks/shuffles, AND bot personality sampling + softmax selection.
- Per-bot streams via `derive(playerId)` so bots are reproducible but independent; per-decision via `derive(playerId + ":" + turn)` if needed.
- **Engine change required:** replace any `Math.random()` / unseeded RNG in dice/card logic with the injected source. This also makes production bugs reproducible.
- 🔶 **ASK HAUKI:** confirm where dice/cards currently get randomness so the seam goes in the right place.

---

## 10. Phase function

```java
enum Phase { EARLY, MID, LATE }
Phase phaseOf(SessionState state);   // from monopolies-on-board, cash levels, properties owned,
                                     // players remaining — NOT turn count alone
```
Pure function; thresholds live in `BotParams` (data, not constants). See research report §B.

---

## 11. `SessionState` constructibility (for tests)

`SessionState` is a `record` (pure value: `sessionId, version, status, seats, players, properties, turn, pendingDecision, auctionState, activeDebt, tradeState, …`) with `of(...)` factories — good, it's constructible. Provide a **test builder** (`SessionStateBuilder`/fixtures) so considerations can be unit-tested against hand-built states ("given this board, the bot must not re-propose X"). If any production path makes state hard to build in isolation, fix that first — it's a prerequisite for testability.

---

## 12. Strategy & config versioning

- Strategy id = `name()` + `params.id` + `configHash`. Every harness report records all three.
- Bump the version on any behavior-affecting change so gauntlet results stay comparable over time.

---

## 13. What is frozen vs. open

**Frozen by this doc:** the Planner/Executor split; Intent as the sole decision currency; BotMemory/ProposalLedger as the single trade-memory authority; multiplicative-with-compensation combination; RandomSource determinism; config-driven weights/curves/personality.

**Open (🔶 decide with Hauki):** ledger permanence vs decay (§5); archetypes vs free sampling (§8); RNG seam location (§9); `maxRounds`, table composition, promotion thresholds (from harness doc §10).
