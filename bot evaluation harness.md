# Bot Evaluation Harness — Design

**Status:** Design proposal (not yet implemented)
**Purpose:** Objectively decide whether one bot strategy (e.g. the new utility-based bot) is *better* than another (e.g. the existing `PureDomainBotDriver`), and catch regressions like infinite trade loops or stalemates automatically.

This document is the companion to the bot-logic refactor (utility-based, parameterized, planner/executor). It defines how we *measure* bots so the refactor can be driven by evidence instead of vibes.

---

## 1. The core problem: Monopoly is extremely high-variance

Monopoly outcomes are dominated by luck (dice, card order, who lands on what first). A few games tell you almost nothing. If you watch 5 games and the new bot wins 3, that is **not** evidence it is better — that result is well within pure chance.

Three consequences shape the whole design:

1. **We need many games** (hundreds to thousands) per comparison, run **headless and fast** — not through the SSE/client/animation stack.
2. **We must control for luck and seat order.** First-player and dice luck create large swings. We neutralize them with deterministic seeded RNG and *mirror matches* (same dice, swapped seats).
3. **We report win-rate with confidence intervals**, not raw counts, so "better" means *statistically* better, not "won more this time."

---

## 2. Design principles

- **Headless.** A game runs as a pure loop over the domain model: `while not over → ask current player's strategy for a command → apply it → advance`. No SSE, no `ClientSessionListener`, no animation, no `computeDelay`, no `viewerCount`. Target throughput: thousands of games per minute on one machine.
- **Deterministic.** All randomness (dice, card decks, shuffles, and the bot's own personality sampling / decision noise) flows through an injected, seeded RNG. Same seed + same strategies ⇒ byte-identical game. This makes failures reproducible and enables variance reduction.
- **Paired / mirror matches (common random numbers).** The single biggest variance reducer. For each seed, play the game once, then replay the **same dice/card sequence** with the strategies assigned to **swapped seats**. Luck cancels out; what's left is strategy difference. This can cut the games needed for significance by an order of magnitude.
- **Seat rotation.** Across the match set, every strategy occupies every seat position equally, so turn-order advantage averages out.
- **Termination cap = stalemate detector.** Every game has a hard round cap. Hitting it is recorded as a `STALEMATE` outcome (no winner) — this is exactly how we'll catch infinite trade loops and "bots never finish" regressions as a first-class metric.
- **One knob at a time.** When tuning weights/curves, change one parameter, re-run the fixed match set, compare. The harness is the regression test for behavior.

---

## 3. Architecture

```
                 ┌────────────────────┐
                 │   MatchScheduler   │  builds match configs (seeds, seat
                 │                    │  assignments, mirror pairs), runs them
                 └─────────┬──────────┘  in parallel, aggregates results
                           │ List<MatchConfig>
                           ▼
                 ┌────────────────────┐
                 │ HeadlessGameRunner │  plays ONE game deterministically,
                 │                    │  returns a GameResult
                 └─────────┬──────────┘
                           │ for each turn
                           ▼
        ┌──────────────────────────────────────────┐
        │ BotStrategy.decide(state, memory) → Intent│  (pure-ish; no I/O)
        │   • PureDomainStrategy  (wraps old driver) │
        │   • UtilityStrategy     (new planner)      │
        └──────────────────────────────────────────┘
                           │ Intent
                           ▼
                 Executor applies Intent as domain
                 command(s) to the headless engine
```

### 3.1 `BotStrategy` — the comparison seam

The same interface both implementations satisfy. This is the contract the harness drives.

```java
public interface BotStrategy {
    /** Decide the next action for `botId` given the current state. Pure: no game mutation, no I/O. */
    Intent decide(GameState state, BotMemory memory, BotParams params, RandomSource rng);

    String name();          // e.g. "pure-domain-v1", "utility-v3"
}
```

- **`PureDomainStrategy`** adapts the existing logic. The current driver is *reactive* (`onSnapshotChanged` → one command). For the harness we need a **synchronous decision entry point**: "given this state, what one command would you issue?" In practice this means extracting the decision body of `onSnapshotChanged` / `handleTradeDecision` / `tryInitiateStrategicTrade` into a callable that returns a command instead of calling `publisher.handle(...)`. (This extraction is itself useful for the refactor and is the first migration step.)
- **`UtilityStrategy`** is the new planner: `decide` runs considerations → response curves → weighting → selection → `Intent`. Already pure by construction, so it drops straight in.

> The key enabler is that **decision logic must be separable from command execution.** The planner/executor split in the refactor is what makes both bots harness-runnable. If the old driver can't be cleanly adapted, wrap as much of it as possible and accept that some old-bot decisions fall back to a simplified path in the harness — document any such gaps.

### 3.2 `HeadlessGameRunner`

```java
public final class HeadlessGameRunner {
    GameResult play(MatchConfig config);   // deterministic given config.seed
}

public record MatchConfig(
    long seed,                              // drives dice, cards, AND bot noise
    List<SeatAssignment> seats,             // seat index → (strategy, params)
    int maxRounds,                          // hard cap → STALEMATE if exceeded
    boolean collectPerTurnTrace             // optional, heavier; off for bulk runs
) {}
```

The runner:
1. Builds a fresh `GameState` with N players seated.
2. Loops: determine the player/decision required → call that seat's `BotStrategy.decide(...)` → translate `Intent` to command(s) → apply to the engine → check terminal conditions.
3. Records metrics (see §4) as it goes.
4. Stops on win, single survivor, or `maxRounds` cap.
5. Returns a `GameResult`.

**Determinism requirement:** dice and card draws must come from `config.seed`. If the engine currently uses `Math.random()` or an internal RNG, the harness needs a seam to inject a seeded `RandomSource`. (Worth doing anyway — it makes *production* bugs reproducible too.)

### 3.3 `MatchScheduler`

Generates the experiment, runs it (parallel across CPU cores — games are independent), aggregates.

```java
public final class MatchScheduler {
    EvaluationReport run(ExperimentSpec spec);
}
```

`ExperimentSpec` defines: which strategies, table composition(s), number of seeds, whether to mirror, max rounds, output path.

---

## 4. Metrics

### 4.1 Primary metric — win share with confidence interval

- **Win share** of a strategy = (games won by that strategy) / (games it played). In a fair N-player game the null/"no skill difference" baseline is **1/N**.
- Report a **Wilson 95% confidence interval** on each win share (better than normal approximation for proportions near 0/1 and for moderate sample sizes).
- **"Better" = its win-share CI is above the opponent's CI and above 1/N**, i.e. the intervals don't overlap.

Wilson interval for `k` wins in `n` games (z = 1.96 for 95%):

```
p̂ = k / n
center = (p̂ + z²/2n) / (1 + z²/n)
halfwidth = (z / (1 + z²/n)) · sqrt( p̂(1−p̂)/n + z²/4n² )
CI = center ± halfwidth
```

### 4.2 Secondary / health metrics (per strategy, aggregated)

| Metric | Why it matters |
|---|---|
| **Stalemate rate** (% games hitting `maxRounds`) | Directly catches infinite loops / non-termination. Should be ~0%. A regression here is a hard fail regardless of win-rate. |
| **Avg game length** (rounds) | Too short = degenerate; too long / climbing = pacing or loop problems. Also a "fun band" proxy. |
| **Bankruptcy rate / avg survival turns** | Catches reckless spending (utility `BankruptcySafety` veto working?). |
| **Trades proposed / accepted / declined per game** | Liveness. Near-zero = "bots never trade" (stalemate-prone, boring). Very high with low accept-rate = loop-spam. There's a healthy band. |
| **Monopolies completed; houses/hotels built; auctions won** | Strategic competence signals; lets you see *how* a bot wins, not just that it does. |
| **Avg final net worth (non-winners)** | Skill gradient even among losers. |
| **Exceptions / illegal-move attempts** | Robustness. Any > 0 is a bug to fix before trusting results. |

### 4.3 Loop / stall detection (first-class, because it's our known pain)

Beyond the `maxRounds` cap, add cheap in-game detectors:
- **State-signature repetition:** hash a compact signature of (ownership, cash bucket, positions, active-trade target). If the same signature recurs > K times with no terminal progress, flag `LOOP_SUSPECTED` and end the game.
- **Trade-without-progress counter:** count consecutive resolved trades that leave net worth / ownership unchanged across all players; above a threshold ⇒ flag.

These give you a *count of loop incidents per 1000 games* as a regression metric — so the thing that keeps biting us becomes a number on a dashboard.

### 4.4 Human-likeness (softer, proxy + optional human test)

Hard to measure directly; use proxies plus an optional human A/B:
- **Decision entropy / variety:** distribution of action types per bot; with personality vectors, distinct archetypes should show distinct distributions. Low entropy across all bots = robotic sameness.
- **"Fun band" checks:** game length in a target range, trade liveness in a healthy band, no degenerate loops, no bot that *never* trades and none that *always* dumps monopolies.
- **Optional human A/B protocol:** blind playtests where a human plays a table of old bots vs a table of new bots (not told which), then rates believability/enjoyment. Small N, qualitative, but the only true measure of "human-like." Keep it for milestone checkpoints, not every change.

---

## 5. Experimental designs (matchups)

Run several; each answers a different question.

1. **Mirror duel (cleanest skill signal).** Table of M `old` + M `new`. For each seed, play once, then replay the **same seed with strategies swapped between the two camps' seats**. Aggregate win share per camp. Common random numbers + swap ⇒ luck largely cancels. This is the primary "is new better than old" test.
2. **Realistic mixed table.** The composition you actually play (e.g. 1 human-substitute slot filled by a baseline + a mix of old/new bots, or all-bot 6-player with 3 old + 3 new). Reflects real conditions; seats rotated.
3. **Gauntlet vs a fixed champion (the ratchet).** Keep the current best bot as a frozen benchmark opponent. A new version is **promoted only if it beats the reigning champion by ≥ X% win share over ≥ N games with non-overlapping CIs.** This prevents drift and gives a clear "did this change help?" gate for every tweak. The old `PureDomainBotDriver` is the permanent baseline at the bottom of the ladder.
4. **Sanity floor.** Every bot must beat a `RandomLegalMove` bot by a wide margin (e.g. ≥ 80% win share heads-up-ish). If it can't, it's broken regardless of how it does vs other bots.
5. **Self-consistency.** `new` vs `new` (different personality seeds) should yield ~fair win shares (≈1/N) with no stalemates — confirms personalities are varied but balanced and the system terminates.

---

## 6. Statistical rigor & sample sizes

- **How many games?** Rough guide for detecting a win-share gap at 95% confidence in a 1v-field setting:
  - distinguishing 55% from 50%: ~1,000+ games
  - distinguishing 52% from 50%: ~6,000+ games
  - mirror matching reduces these substantially because it removes shared luck variance.
  Start each comparison at **2,000 games** (1,000 mirror pairs); increase if CIs overlap.
- **Always report CIs**, never bare win counts. Overlapping CIs ⇒ "no detectable difference yet," run more games.
- **Variance reduction:** mirror matches (common random numbers) are the main lever; also fix the same set of seeds across the *old* and *new* evaluation runs so you compare on identical luck.
- **Beware multiple comparisons:** if you test many weight variants against the champion, the best by chance will look good. Re-validate the winner on a fresh seed set before promoting.
- **Reproducibility:** log the exact strategy versions, params/config hash, seed set, and harness version in every report so any result can be regenerated.

---

## 7. Output format

Two artifacts per experiment:

**(a) Per-game CSV/JSON** (one row per game) for deep analysis:
```
seed, mirror_pair_id, seats(json), winner_strategy, winner_seat,
outcome(WIN|STALEMATE|LOOP_SUSPECTED), rounds,
per_strategy_stats(json: bankruptcies, trades_prop/acc/dec, monopolies,
houses, auctions_won, final_net_worth), exceptions
```

**(b) Aggregate report** (`EvaluationReport`, human-readable + JSON):
```
Experiment: utility-v3 vs pure-domain-v1   (mirror duel, 3v3)
Games: 2000 (1000 mirror pairs)   seed range: ...
config hash: ...   harness: v1.0

WIN SHARE
  utility-v3     54.8%  [52.6%, 57.0%]   ← CI above 50% and above pure-domain
  pure-domain-v1 45.2%  [43.0%, 47.4%]
  → utility-v3 is better (CIs disjoint, gap 9.6pp)

HEALTH
  stalemate rate: utility 0.1% | pure-domain 0.4%
  loop_suspected: utility 0 | pure-domain 7   ← old loop bug visible here
  avg rounds: 88   |  bankruptcies/g: ...
  trades/g (prop/acc/dec): utility 6.2/2.1/4.1 | pure-domain 9.8/1.0/8.8  ← old over-proposes
```

This makes the old loop bug and the new bot's improvement both *visible as numbers*.

---

## 8. Build order (phasing)

Build the harness incrementally; each step is independently useful.

1. **Inject a seeded `RandomSource`** into dice/card draws in the engine. (Also fixes prod reproducibility.)
2. **Extract `BotStrategy.decide(...)`** from the existing driver (synchronous, returns a command/intent). This is also migration step 1 of the refactor.
3. **`HeadlessGameRunner`** that plays one deterministic game to terminal/`maxRounds` and returns a minimal `GameResult` (winner, rounds, outcome).
4. **`MatchScheduler`** + parallel execution + Wilson-interval aggregation + CSV/report output.
5. **Mirror matching** (swap seats on the same seed).
6. **Health & loop metrics** (§4.2–4.3).
7. **`UtilityStrategy`** plugged in; run the gauntlet vs `pure-domain-v1`.
8. (Optional, later) **CMA-ES / self-play weight tuning** loop on top of the harness — only after hand-tuning plateaus.

Steps 1–4 already let you answer "is A better than B?" for *any* two strategies. Everything after is depth.

---

## 9. How this plugs into the refactor

- The harness needs the **planner/executor split** to run both bots — so building the harness and starting the refactor share their first two steps (the `BotStrategy` seam + synchronous `decide`).
- During migration, after each decision is moved from old → utility, run the **gauntlet** to confirm the utility version is ≥ the old one before relying on it. The harness is the safety net that lets you refactor aggressively without guessing.
- Loop/stalemate metrics mean the class of bug that's been biting us (re-proposing declined trades) can never silently regress again — it shows up as `loop_suspected > 0` in CI.

---

## 10. Open decisions (for us to pick)

1. **Table composition for the primary comparison:** pure 6-bot all-AI (fast, clean) vs. tables that include a scripted "human stand-in" (more realistic to how you actually play). Recommendation: use all-AI mirror duels for tuning/regression, and a realistic mixed table for milestone sanity checks.
2. **`maxRounds` value:** high enough that legitimate long games finish (Monopoly can run long), low enough that loops are caught quickly. Suggest starting ~300 rounds and tuning from observed legit-game length distribution.
3. **Promotion threshold X% and games N** for the gauntlet ratchet. Suggest: ≥ 3pp win-share gain, ≥ 2,000 games, disjoint 95% CIs.
4. **Where the harness lives:** a separate Gradle/Maven module / `src/test`-style runner vs. a small CLI. A CLI (`./run-eval --a utility-v3 --b pure-domain-v1 --games 2000 --mirror`) is convenient for iterating.
