# Utility-Based Money Decisions for Monopoly Bots — Research & Rationale

> Background/rationale doc for the bot refactor. Read first (it is step 1 of `START-HERE-bot-refactor.md`). The concrete numbers here seed the considerations and `BotParams` defaults defined in `bot-contracts-and-interfaces.md`.

## TL;DR
- **Adopt a utility-AI architecture modeled on Dave Mark's Infinite Axis Utility System (IAUS):** score every candidate money action (buy, build, trade, bid, hold cash) with the same normalized 0–1 utility, built from independent "considerations" run through response curves and combined multiplicatively with a compensation factor — then pick (or weighted-randomly sample) the top action. Replaces scattered guard conditions with one comparable scale; makes goals data-driven via externalized weights/curves.
- **Make goals personality- and phase-parameterized:** each bot gets a stable vector (aggression, risk tolerance, monopoly-appetite, liquidity preference, trade-willingness, vindictiveness) sampled per-agent and held constant; weights are multiplied/shifted by game phase (early acquisition → mid-game building → late-game liquidity/survival). Seed Monopoly numbers from theory: orange/red/light-blue are highest-ROI groups, the "3-house rule" is the efficiency sweet spot, Markov landing probabilities define rent expectations.
- **Migrate incrementally:** keep `PureDomainBotDriver` as a fallback behind a strategy interface, introduce a pure Planner (state+memory → intent) separate from an Executor (intent → incremental commands), unit-test considerations as pure functions, optionally auto-tune weights later via self-play/CMA-ES. Hand-tune first; learning is optional and high-effort.

## Key findings

**1. Utility AI fits heterogeneous money decisions.** Utility theory gives every action a single uniform value so non-comparable options (trade vs. house vs. bid) rank on one scale (David "Rez" Graham, *Game AI Pro*, "An Introduction to Utility Theory"). Loop: score every action, choose highest (Maximum Expected Utility) or seed a weighted-random selection for variety. Dave Mark's IAUS (GDC 2013/2015, "Building a Better Centaur") is the canonical concrete framework: each Action has Considerations; each Consideration is an input axis normalized to [0,1] then passed through a Response Curve; consideration scores combine into the action's score.

**2. Combination math.**
- **Additive (weighted sum/mean):** strength on one objective compensates for weakness on another; no single low factor vetoes. Use when objectives are independent.
- **Multiplicative (product/geometric mean):** any consideration scoring 0 zeroes the action — a built-in veto/early-out (e.g. "can't afford" → 0). IAUS uses this. Mike Lewis (*Game AI Pro 3*, ch. 13): "any consideration can disqualify an entire decision from being selected simply by scoring zero."
- **Multiplication problem + compensation factor:** multiplying many [0,1] factors decays toward 0 (0.9⁹ ≈ 0.387). Dave Mark's fix (confirmed across implementer sources):
  - `modificationFactor = 1 − (1 / numConsiderations)`
  - `makeUpValue = (1 − score) × modificationFactor`
  - `finalScore = score + (makeUpValue × score)`
- **Rule of thumb** (*UtilityMax*, arXiv): if one objective failing should collapse the whole utility → multiplicative; if remaining objectives should still contribute → additive. For Monopoly money decisions, **multiplicative with veto considerations (affordability, bankruptcy-safety) is the default.**
- **Response curves:** linear, polynomial, logistic (S-curve), exponential — map raw input to utility (Mark, *Behavioral Mathematics for Game AI*). Put the steepest gradient at the meaningful threshold (e.g. reserve ≈ one lap's rent).
- **Inertia:** momentum bonus (×1.1–1.25 on last chosen action) avoids oscillation between near-ties.

**3. Monopoly numbers to hard-code as defaults (then expose as data).**
- **Landing probabilities:** Jail most-occupied (~3.95%); most-landed property is Illinois Ave (Red). Per Truman Collins, "about 3.18% of rolls land on the most probable non-jail square, Illinois Avenue." Orange group is hit most coming out of Jail (6/8/9 spaces away; ~38.9% chance to hit an orange on the exit roll).
- **Group ranking (prob × ROI w/ 3 houses), best→worst:** Orange, Dark Blue, Red, Yellow, Green, Pink, Light Blue, Brown.
- **Expected $/opponent roll** (Gartland/Burson/Ferguson, "A Markovian Exploration of Monopoly," Univ. of Illinois, 2014). **Hotels:** green $96.25, yellow $87.91, red $86.87, orange $81.52, blue $80.43, pink $57.31, light-blue $36.77, brown $14.26. **Three houses:** green $68.22, yellow $61.53, red $58.41, blue $57.33, orange $47.81, pink $33.41, light-blue $18.17, brown $5.50.
- **3-house rule:** marginal rent jumps hugely through the 3rd house then flattens (Illinois Ave: 1→2 +200%, 2→3 +150%, 3→4 only +23%). Building to three also triggers the housing shortage (only 32 houses exist), denying opponents development.
- **Cash reserve:** survive one lap. Early ~$100–150; mid enough to build 3 houses on your set; late $500+ to absorb a hotel hit.
- **Jail flips by phase:** early game out fast (keep acquiring); late game stay in (dodge developed-property rent, still collect your own).
- **Trades:** don't accept a "fair" both-sides-monopoly trade unless yours is clearly stronger; price the opponent's last missing piece at a steep premium (holdout/leverage); handing a monopoly to a cash-poor opponent is safer (they can't build); in 3+ players a slightly unfavorable trade can be correct because it burdens *all* opponents; bots must be willing to trade — pure non-traders cause stalemates.

**4. Personality vectors create believable variety.** Commercial AI encodes personality as numeric trait vectors held constant per agent (Civ: Competitiveness/Boldness/Loyalty; Total War traits; Wing Commander pilots: Aggressiveness/Carefulness/Courage/Loyalty). The Sims (Richard Evans) added personality to utility scoring with a Boltzmann/temperature so unhappy Sims occasionally pick lower-utility actions. Research consensus: human-like, slightly sub-optimal, *consistent* opponents are more fun than optimal ones (Soni & Hingston, "Bots Trained to Play Like a Human Are More Fun," IEEE IJCNN 2008: "the aim is to provide interesting opponents… not optimal ones"; the human-like bots were "clearly preferred"). Sid Meier deliberately weakened exploitative Civ AI. Key: personality biases the *weights/curves per agent*, not re-rolled per decision.

**5. Data-driven externalization is standard.** IAUS was designed as data-driven so designers author behaviors in a tool ("as little as seven minutes" per the GDC 2015 time-lapse). Externalize weights/curves to JSON for hot-tuning and reproducibility. Keep considerations as pure functions for trivial unit testing.

**6. Auto-tuning is optional/high-effort.** CMA-ES is the standard derivative-free optimizer for tuning evaluation weights via self-play (used for Othello, Mahjong, Dragonchess heuristics). Monopoly RL exists: Bonjour et al. ("Decision Making in Monopoly Using a Hybrid Deep RL Approach," arXiv:2103.00683) report a hybrid PPO agent at 91.65% win-rate vs. a fixed champion-strategy agent, >20% over standard PPO — but with heavy state/action engineering. For a hobby project: hand-tuned weights + a self-play win-rate harness is the high-payoff path; full RL is not worth it.

**7. Structure: separate scoring, planning, execution.** Planner (state+memory → intent, deterministic) separate from Executor (intent → incremental commands) yields modularity, testability, predictable control flow.

## Architecture summary (see contracts doc for exact types)

- **Considerations:** pure `f(state, memory, params) → [0,1]` (Affordability/BankruptcySafety as vetoes; GroupCompletion, GroupValue, LandingProbability, OpponentDenial, RoiOfDevelopment, LiquidityPreference).
- **Actions:** multiply considerations + compensation factor, × category weight (normal/important/emergency) × phase multiplier × personality multiplier.
- **Selector:** argmax for strength; softmax/top-N weighted-random with per-agent temperature for variety; momentum to avoid dithering.
- **Continuous choices (bids, trade cash):** score a few discretized candidate amounts and pick the best; floor bids at mortgage value; bid up properties opponents need to drain their cash.
- **Phase:** detect from monopolies on board, cash, properties owned, players left — encode as phase-dependent weights/curves.
- **Personality:** per-agent vector sampled once from an archetype + jitter; keep a TradeWillingness floor > 0 to avoid stalemate bots.
- **Trades:** price via the same utility on the resulting state; premium on opponents' last pieces; accept slightly-negative trades when opponent-burden offsets; gate "give monopoly" on opponent cash.

## Recommendations
1. Start with the interface + Planner/Executor split + ONE migrated decision (buy/auction); keep `PureDomainBotDriver` default. Require the utility version to match/beat the old win-rate in ≥1,000 self-play games before migrating the next decision.
2. Use multiplicative + compensation factor with veto considerations; additive only where compensation between factors is genuinely desired.
3. Seed considerations with the published numbers (group priority, 3-house rule, per-roll $ table, ~one-lap reserve, phase-flipped jail). Hard-code defaults, expose as JSON.
4. Ship ~5 archetype vectors with jitter, constant per game; softmax/top-N with per-agent temperature; verify variety via decision distributions and competence via a win-rate floor vs. random (≥80%).
5. Defer learning; hand-tune first. Only add CMA-ES if hand-tuning plateaus and compute allows — and optimize a handful of weights, not a neural net.
6. Threshold tuning: stalemates → raise TradeWillingness floor + opponent-burden weight; robotic → raise temperature; frequent bankruptcies → raise cash_min/LiquidityPreference; over-hoarding → lower it. One parameter at a time vs. self-play metrics.

## Caveats
- Enthusiast strategy figures vary with jail assumptions and house rules; the Markov academic papers and Collins's simulation are authoritative for probabilities/expected values. Treat dollar tables as relative rankings, not exact for your ruleset.
- The IAUS compensation-factor formula's primary source is the GDC Vault video; it's consistent across implementations.
- "Optimal" vs "fun" diverge — the sub-optimality/personality layer is a feature, but guard against *incompetent* sub-optimality (bankruptcy, never trading) with veto considerations and parameter floors.
- Cited RL/CMA-ES win-rates come from controlled setups vs. fixed agents; evidence the approach works, not drop-in numbers.
- Auction/trade rules vary between physical, tournament, and video-game variants — match the bot's assumptions to *your* engine's exact rules.
