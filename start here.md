# START HERE — Bot Refactor Playbook (for the implementing agent)

You are implementing a refactor of the Monopoly bot logic toward a **utility-based, parameterized, testable** design, plus an **evaluation harness** to prove the new bot is better than the old one. This file tells you what to read, how to work, and the exact order to build things — **piece by piece, each piece verified before the next.**

Do **not** attempt the whole thing in one pass. Do **not** break the existing bot. Ask the human (Hauki) at the marked checkpoints.

---

## 0. Read these first, in this order

1. **`bot-utility-architecture.md`** — the research report: *why* utility AI, the Monopoly valuation numbers, personality/phase model, and the recommended structure. This is the rationale and the source of concrete numbers.
2. **`bot-evaluation-harness.md`** — how to *measure* old vs new (headless sim, determinism, mirror matches, win-rate + Wilson CIs, loop/stalemate metrics). You will build this early because it is how you verify every later step.
3. **`bot-contracts-and-interfaces.md`** — the *frozen seams*: Intent algebra, decision-point model, BotStrategy, BotMemory/ProposalLedger, TradeProposal, Consideration/Curve/BotParams, RandomSource. These are the contracts you implement against. If reality in the code differs from a signature there, update that doc in the same PR.
4. This playbook.

The target code is `monopoly-backend`, primarily `src/main/java/fi/monopoly/server/session/PureDomainBotDriver.java` and the domain under `fi/monopoly/domain/...`.

---

## 1. Operating principles (apply to every step)

- **Keep the old bot working the entire time.** `PureDomainBotDriver` stays as a `BotStrategy` named `pure-domain-v1` and remains the production default until a replacement provably beats it. Everything new goes **behind a per-bot/per-match flag**.
- **One decision at a time.** Migrate a single decision type (buy, build, trade, …) per step. Never "rewrite trading" in one go.
- **Verify before moving on.** Each step has a Definition of Done (DoD) and a verification gate. Do not start the next step until the current one passes.
- **Pure functions, explicit memory.** No new hidden mutable fields on the driver. Cross-turn state goes in `BotMemory`/`ProposalLedger`, passed explicitly. (This is what eliminates the trade-loop class of bug.)
- **Confirm types against code.** The contracts doc marks illustrative signatures _(verify in code)_. Confirm real field/accessor/command names before coding; if they differ, fix the code to the contract OR update the contract, and note it.
- **Run the tests + the harness.** After each step: `mvn test` (or the project's build) must pass; once the harness exists, run the relevant gate (parity or gauntlet) and paste the numbers into the PR description.
- **Small, reviewable diffs.** Prefer additive new classes over rewriting the old driver. Present diffs; don't dump whole files unless asked.
- **Ask Hauki at 🔶 checkpoints** and before changing any production default. Hauki works on mobile and prefers complete-file or clear-diff delivery, backend changes before client, and conservative scope. Don't guess on balance-sensitive or product decisions — ask.

---

## 2. Decision checkpoints — ask Hauki

Pause and ask before proceeding when you hit any of these (also flagged inline):

- 🔶 **Ledger permanence vs decay** (contracts §5) — permanent per game, or reset when the board materially changes?
- 🔶 **Personality: fixed archetypes vs free sampling** (contracts §8).
- 🔶 **RandomSource seam location** (contracts §9) — confirm where dice/cards get randomness today.
- 🔶 **Harness parameters** (harness §10) — `maxRounds`, table composition for the primary comparison, gauntlet promotion threshold (X% win-share gain, N games).
- 🔶 **Promoting any migrated decision to the production default** — never flip the default without Hauki's OK and a passing gauntlet.
- 🔶 **If a step turns out larger/riskier than its DoD implies** — stop and report scope before ballooning the change.

When unsure whether something is a checkpoint: it probably is. Ask.

---

## 3. Build order (phased, each step independently shippable)

### Phase 0 — Prerequisites (make the system measurable & testable)
**0.1 RandomSource seam.** Introduce `RandomSource` (contracts §9). Replace unseeded randomness in dice/card/shuffle logic with the injected source. DoD: a game seeded with S is byte-identical on replay. Verify: a tiny test that plays the same seed twice and asserts identical event log. 🔶 confirm seam location first.
**0.2 SessionState test builder.** Provide `SessionStateBuilder`/fixtures so arbitrary boards can be constructed for unit tests (contracts §11). DoD: can build a state with chosen ownership/cash/phase/trade in <10 lines.
**0.3 Golden-master capture of the old bot.** Before touching the driver, record its decisions on a fixed set of seeded states (a characterization test). DoD: a stored corpus + a test that replays it. This is your safety net for Phase 1.

### Phase 1 — The `BotStrategy` seam (no behavior change)
**1.1** Define `BotStrategy`, `Intent` (full algebra, contracts §2), and the Intent↔Command table (contracts §3 — fill & verify every case; prove no decision is unmapped).
**1.2** Extract a synchronous `decide(state, memory, params, rng) → Intent` from the old driver's `dispatchGreedy`/`handle*` methods, WITHOUT changing what it decides — it just returns an Intent instead of calling `publisher.handle`. Wrap as `PureDomainStrategy` (`pure-domain-v1`).
**1.3** Add an `Executor` that turns an Intent into the existing command(s) and applies them (production path: via `publisher`).
**1.4** Wire production to run through `PureDomainStrategy` + `Executor` behind a flag; default stays exactly today's behavior.
**DoD:** golden-master (0.3) passes — the extracted path reproduces the old bot's decisions exactly. Verify: characterization test green; a manual playtest shows no change.

### Phase 2 — The evaluation harness (now you can measure)
**2.1** `HeadlessGameRunner` (harness §3.2): plays one deterministic game to terminal/`maxRounds`, returns `GameResult`. Reuses the real engine (decision-point model §1), no SSE/animation.
**2.2** `MatchScheduler` + parallel runs + Wilson-CI aggregation + CSV/report output (harness §3.3, §4, §7).
**2.3** Mirror matching (same seed, swapped seats) and seat rotation (harness §2, §5).
**2.4** Health + loop/stalemate metrics (harness §4.2–4.3) — including the `loop_suspected` counter, which must read 0 for a healthy bot.
**DoD:** can run `A vs B, 2000 games, mirror` and get win-shares with CIs + health metrics. Verify: `pure-domain-v1` vs `RandomLegalMove` shows ≥80% win share (sanity floor); `pure-domain-v1` vs itself shows ≈fair shares with ~0 stalemates. 🔶 confirm harness params.

> After Phase 2 you can answer "is A better than B?" for any two strategies. Everything below is measured against this.

### Phase 3 — Utility skeleton + first migrated decision
**3.1** Implement `Consideration`, `Curve`, `BotParams`, `Personality`, `phaseOf`, the multiplicative+compensation combiner, and the selector (contracts §7–§10; research §A–§B). All pure, all unit-tested.
**3.2** Implement `ProposalLedger` + `TradeProposal` (contracts §5–§6) — even though trades migrate later, the ledger underpins them.
**3.3** Build `UtilityStrategy` that handles **one** decision (recommend **buy-vs-auction**) via the utility model and **delegates everything else to `PureDomainStrategy`**.
**DoD:** `utility(buy-only) vs pure-domain-v1` gauntlet ≥ parity, 0 new stalemates. Unit tests for each consideration (table-driven, built states). 🔶 before making it default.

### Phase 4 — Migrate decisions one at a time
For each of: **building → mortgaging → debt resolution → auction bidding → trades** (do trades last; they're the hardest and the loop-prone one):
- Implement the decision in `UtilityStrategy`; keep delegation for the rest.
- Add considerations + curves; unit-test them.
- For **trades**: the Planner generates `TradeProposal` candidates, filters via `ledger.isFailed(...)` (the structural loop guard), scores, selects; the Executor runs the Open→Edit→Submit / Accept/Counter/Decline protocol and records ledger outcomes at the decision point.
- **Gate:** gauntlet vs the current champion ≥ promotion threshold, `loop_suspected == 0`, stalemate rate ≈ 0.
- 🔶 ask before promoting each to default.
**DoD per decision:** champion gauntlet passed; health metrics clean; tests green.

### Phase 5 — Personality, phase modulation, externalized config
**5.1** Move weights/curves/phase-multipliers/personality to JSON `BotParams`; validate on load; content-hash for reports (contracts §8, §12).
**5.2** Ship the agreed archetypes (🔶 §8) with jitter, sampled via the game RNG.
**5.3** Verify variety (decision-distribution differs across archetypes) and competence floor (each archetype beats Random handily), and that a mixed 5-bot table feels distinct but balanced (≈fair shares, no stalemates).
**DoD:** config-only tuning works without recompiling; archetypes measurably distinct yet all competent.

### Phase 6 — (Optional, later) auto-tuning
Only if hand-tuning plateaus and compute allows: a self-play loop using the harness as the objective, tuning the weight/curve parameters with CMA-ES (research §6). Keep `pure-domain-v1` as the permanent baseline opponent. Not required for success.

---

## 4. How to verify at each gate

- **Build/tests:** the project build + `mvn test` must be green. (If you cannot compile in your environment, say so explicitly and have Hauki run it — do not claim verification you didn't do.)
- **Parity gate (Phase 1):** golden-master characterization test reproduces old decisions exactly.
- **Gauntlet gate (Phases 3–5):** harness run of new vs current champion, ≥ threshold win-share with disjoint 95% CIs, `loop_suspected == 0`, stalemate ≈ 0. Paste the report into the PR.
- **Sanity floor:** every bot beats `RandomLegalMove` by a wide margin.

Never report a step "done" without its gate passing. If a gate fails, fix or report — don't proceed.

---

## 5. Working agreement with Hauki

- Backend changes before client changes. Conservative scope: one variable/decision at a time.
- Deliver complete files or clear diffs (Hauki often works on mobile; large files go as downloadable artifacts, not pasted into chat).
- When a result is balance-sensitive or product-shaped, present options and **ask** rather than deciding unilaterally.
- Keep the docs in sync: if the code forces a contract change, update `bot-contracts-and-interfaces.md` in the same change and note why.
- The old bot is never deleted during the migration; it remains the fallback and the benchmark.

---

## 6. Definition of "done" for the whole effort

- `UtilityStrategy` handles all money decisions, is config-driven, and **beats `pure-domain-v1`** in the gauntlet with clean health metrics.
- The trade-loop class of bug is **structurally impossible** (ledger-filtered planning) and shows `loop_suspected == 0` in CI.
- Bots feel distinct and human-like (archetypes + phase modulation + selection temperature), verified by metrics and an optional human A/B check.
- The old bot remains available as a fallback/baseline, and the harness can compare any two strategies on demand.
