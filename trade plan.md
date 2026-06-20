# Designing a Parameterized, Utility-Based Money-Decision System for Monopoly Bots

## TL;DR
- **Adopt a utility-AI architecture modeled on Dave Mark's Infinite Axis Utility System (IAUS):** score every candidate money action (buy, build, trade, bid, mortgage, pay debt) with the same normalized 0–1 utility, built from independent "considerations" run through response curves and combined multiplicatively with a compensation factor — then pick (or weighted-randomly sample) the top action. This replaces scattered guard conditions with one comparable scale and makes goals data-driven via externalized weights/curves.
- **Make goals personality- and phase-parameterized:** give each bot a stable vector (aggression, risk tolerance, monopoly-appetite, liquidity preference, trade-willingness, vindictiveness) sampled per-agent and held constant, and multiply/shift weights by game phase (early acquisition → mid-game building → late-game liquidity/survival). Seed the Monopoly-specific numbers from established theory: orange/red/light-blue are the highest-ROI groups, the "3-house rule" is the efficiency sweet spot, and Markov landing probabilities define rent expectations.
- **Migrate incrementally:** keep `PureDomainBotDriver` as a fallback behind a strategy interface, introduce a pure `Planner` (state+memory → intent) separate from an `Executor` that issues incremental game commands, unit-test considerations as pure functions, and optionally auto-tune weights later with self-play/CMA-ES. Start with hand-tuned weights; learning is a high-effort, optional upgrade.

## Key Findings

**1. Utility AI is the right paradigm for heterogeneous money decisions.** Utility theory lets you describe every possible action with a single uniform value so non-comparable options (propose a trade vs. buy a house vs. bid) can be ranked on one scale (David "Rez" Graham, *Game AI Pro*, "An Introduction to Utility Theory"). The core loop: score every action, choose the highest (the Principle of Maximum Expected Utility), or seed a weighted-random selection for variety. Dave Mark's Infinite Axis Utility System (IAUS), presented at GDC 2013 and 2015 ("Building a Better Centaur: AI at Massive Scale"), is the canonical concrete framework: each Action has Considerations, each Consideration is an input (axis) normalized to [0,1] then passed through a Response Curve, and the consideration scores combine into the action's score.

**2. Combination math and its tradeoffs.** Normalized scores combine easily. Options:
- **Weighted sum / arithmetic mean (additive):** strength on one objective compensates for weakness on another; good when objectives are independent and you don't want any single low factor to veto.
- **Multiplication / geometric mean (multiplicative):** any consideration scoring 0 zeroes the whole action — a built-in veto/early-out (e.g. "can't afford it" → 0). IAUS uses this so any consideration can disqualify a decision, and cheaper considerations are ordered first to early-out. [carloslab-ai](https://utilityintelligence.carloslab-ai.com/Documentation/UtilityIntelligence/Considerations/) As Mike Lewis writes in *Game AI Pro 3*, ch. 13 ("Choosing Effective Utility-Based Considerations"): "any consideration can disqualify an entire decision from being selected simply by scoring zero." [Gameaipro](https://www.gameaipro.com/GameAIPro3/GameAIPro3_Chapter13_Choosing_Effective_Utility-Based_Considerations.pdf)
- **The multiplication problem and the Compensation Factor:** multiplying many [0,1] factors drives the product toward 0 as the count grows (0.9⁹ ≈ 0.387). Dave Mark's compensation factor corrects this. The exact formula (confirmed against Dave Mark/Mike Lewis's GDC 2015 talk and multiple independent implementer sources):
  - `modificationFactor = 1 − (1 / numConsiderations)`
  - `makeUpValue = (1 − score) × modificationFactor`
  - `finalScore = score + (makeUpValue × score)`
- **Rule of thumb** (*UtilityMax*, arXiv): "if one objective fails entirely, should the overall utility collapse to zero, or should the remaining objectives still contribute? If the former, use a multiplicative structure. If the latter, use an additive one." [arxiv](https://arxiv.org/pdf/2603.11583) For Monopoly money decisions, multiplicative (with veto considerations like affordability and bankruptcy-safety) is the better default.
- **Response curves:** map a raw input to utility via linear, quadratic, logistic (S-curve), or exponential curves. Mark's *Behavioral Mathematics for Game AI* details these; a designer can shift the S-curve so the steepest gradient sits at the meaningful threshold (e.g. cash reserve ≈ one lap's worth of rent).
- **Inertia / momentum:** to avoid oscillation between near-tied actions, add a momentum bonus (multiply the previously-chosen action's score by ~1.1–1.25) or a "keep running until finished" flag.

**3. Monopoly valuation numbers worth hard-coding as defaults (then exposing as data).**
- **Landing probabilities (per-roll):** Jail is the most-occupied space (~3.95%); the single most-landed property is Illinois Avenue (Red). Per Truman Collins, "Probabilities in the Game of Monopoly," "about 3.18% of the rolls will result in the player landing on the most probable non-jail square, Illinois Avenue" [Tkcs-collins](http://www.tkcs-collins.com/truman/monopoly/monopoly.shtml) (the widely-cited 3.19% figure is from the 32-billion-roll simulation variant assuming players always pay to get out of jail). New York Ave (Orange) ~3.09%, Tennessee ~2.94%, St. James ~2.79%. Orange as a *group* is landed on most because it sits 6/8/9 spaces past Jail — from Jail there is roughly a 38.9% chance of hitting an orange property on the exit roll.
- **Color-group ranking (probability × ROI with 3 houses), best to worst:** Orange, Dark Blue, Red, Yellow, Green, Pink, Light Blue, Brown.
- **Expected dollars per opponent roll** (Gartland/Burson/Ferguson, "A Markovian Exploration of Monopoly," University of Illinois, June 27, 2014). With **hotels**: green $96.25, yellow $87.91, red $86.87, orange $81.52, blue $80.43, pink $57.31, light blue $36.77, brown $14.26. [illinois](https://pi4math.web.illinois.edu/wp-content/uploads/2014/10/Gartland-Burson-Ferguson-Markovopoly.pdf) With **three houses**: green $68.22, yellow $61.53, red $58.41, blue $57.33, orange $47.81, pink $33.41, light blue $18.17, brown $5.50. [illinois](https://pi4math.web.illinois.edu/wp-content/uploads/2014/10/Gartland-Burson-Ferguson-Markovopoly.pdf) (The blue-with-hotels derivation: "1500 · .0205 + 2000 · .0248 = 80.43.") [Illinois](https://pi4math.web.illinois.edu/wp-content/uploads/2014/10/Gartland-Burson-Ferguson-Markovopoly.pdf)
- **The "3-house rule":** marginal rent jumps massively through the third house, then flattens (Illinois Ave: 1→2 houses is +200%, 2→3 is +150%, 3→4 only +23%). [Rempton Games](https://remptongames.com/2020/11/16/the-ultimate-monopoly-strategy-guide/) The Illinois paper concludes: "the additional rent gained by adding a house increases until three houses are erected. Thus, a 3-house develpoment is the most cost efficient." [Illinois](https://pi4math.web.illinois.edu/wp-content/uploads/2014/10/Gartland-Burson-Ferguson-Markovopoly.pdf) Building to three houses also triggers the **housing shortage** (only 32 houses exist), denying opponents the ability to develop.
- **Cash reserve heuristic:** keep enough to survive one lap; early game minimal (~$100–150), mid-game enough to build three houses on your set (~$450 for a $50/house set), late game $500+ to absorb a hotel hit.
- **Jail strategy flips by phase:** early game get out fast to keep acquiring; late game stay in to avoid developed-property rent while still collecting your own rent.
- **Trade theory:** never accept a "fair" trade that gives both sides a monopoly unless yours is clearly stronger; price the opponent's "last missing piece" at a large premium (holdout/leverage value); against cash-poor opponents, handing over a monopoly is safer (they can't build); in 3+ player games, even a slightly unfavorable trade can be correct because it burdens *all* opponents; and bots must be willing to trade — pure non-trading bots cause stalemates.

**4. Personality vectors create believable variety.** Commercial games encode AI personality as numeric trait vectors held constant per agent: *Civilization* uses Competitiveness, Boldness, Loyalty; *Total War* uses traits like "Destroyer"/"Passive"; *Wing Commander* pilots have Aggressiveness, Carefulness, Courage, Loyalty, Verbosity. The Sims (Richard Evans) added personality axes to utility scoring and used a Boltzmann/temperature distribution so unhappy Sims occasionally pick lower-utility actions. [Wikipedia](https://en.wikipedia.org/wiki/Utility_system) Research consensus: players find human-like, slightly unpredictable, sub-optimal opponents *more fun* than optimal ones. Soni & Hingston ("Bots Trained to Play Like a Human Are More Fun," 2008 IEEE IJCNN, pp. 363–369) state "the aim is to provide interesting opponents for human players, not optimal ones," [IEEE Xplore](https://ieeexplore.ieee.org/document/4633818/) and found "the neural network-based opponents were clearly preferred." [IEEE Xplore](https://ieeexplore.ieee.org/document/4633818/) Sid Meier deliberately weakened Civ AI alliance exploitation because perfectly-exploiting AI felt like cheating. The key is consistency: personality should bias the *weights/curves* per agent, not be re-rolled per decision.

**5. Data-driven externalization is standard and valuable.** IAUS was explicitly designed as a data-driven, self-contained architecture where designers author behaviors in a tool with no engineering burden. [Wikipedia](https://en.wikipedia.org/wiki/Utility_system) Per Intrinsic Algorithm's IAUS documentation on the GDC 2015 talk, Mark "demonstrates via a time-lapse video how the modular data-driven design of the IAUS enables designers to construct unique AI packages for their NPCs in as little as seven minutes." Externalizing weights/curves to JSON enables hot-tuning and reproducibility. Keep considerations as pure functions of (state, memory, params) so they're trivially unit-testable.

**6. Auto-tuning is optional and high-effort.** Utility weights can be tuned by self-play. CMA-ES (Covariance Matrix Adaptation Evolution Strategy) is the standard derivative-free optimizer for this: it's been used to evolve game evaluation-function weights (Othello Co-CMA-ES; Sparrow Mahjong LSTM weights; Dragonchess 25 heuristic weights). For Monopoly specifically, RL agents exist: Bailis et al. (AISB) framed Monopoly as an MDP, and Bonjour et al. ("Decision Making in Monopoly Using a Hybrid Deep Reinforcement Learning Approach," arXiv:2103.00683) reported their hybrid PPO agent reaching a 91.65% win rate against a fixed-policy agent [Purdue University](https://www.cs.purdue.edu/homes/bb/hybrid.pdf) "developed based on the Monopoly world champion's strategy," outperforming standard PPO "by more than 20%." [Purdue University](https://www.cs.purdue.edu/homes/bb/hybrid.pdf) But these require huge state/action engineering. For a hobby project, hand-tuned weights + a self-play harness measuring win-rate is the high-payoff path; full RL is not worth it.

**7. Software structure: separate scoring, planning, and execution.** The planner/executor split (deterministic Planner: state+memory → intent; simpler Executor: intent → incremental commands) is a well-established pattern that yields modularity, testability, and predictable control flow.

## Details

### A. The recommended decision architecture

Model the system on IAUS with three layers:

1. **Considerations (pure scoring functions).** Each is `f(GameState, BotMemory, Params) → [0,1]`. Examples for Monopoly:
   - `Affordability` (veto): 0 if cost > available cash (incl. safe reserve), ramping to 1.
   - `BankruptcySafety` (veto): based on the RL-paper condition `cash_current + R_next − cost ≥ cash_min`, where `R_next` is expected rent change next round and `cash_min` is a personality-scaled buffer (from the open-world Monopoly solver, arXiv:2107.04303).
   - `GroupCompletionProgress`: how close this acquisition brings the bot to a monopoly (0 for an isolated property, high for the third in a set).
   - `GroupValue`: normalized from the per-roll expected-dollar table (orange/red/light-blue high).
   - `LandingProbability`: from the Markov steady-state distribution.
   - `OpponentDenial`: value of stopping an opponent completing a set or of grabbing scarce houses.
   - `RoiOfDevelopment`: marginal rent / cost, peaking at the third house.
   - `LiquidityPreference`: penalizes spending below the phase reserve.

2. **Actions / Decisions.** Each action multiplies its considerations (clamped [0,1]) and applies the compensation factor, then a personality- and phase-dependent **weight multiplier** (Mark's category weights: normal/important/emergency). "Pay mandatory debt" is an emergency-weighted action that always dominates when triggered. Action types: BuyProperty, DeclineAndAuction, BidInAuction(amount), BuildHouse/Hotel(property), Mortgage/Unmortgage(property), ProposeTrade(offer), Accept/RejectTrade, PayDebt/RaiseCash.

3. **Selector.** Normally argmax; for human-like variety, use a softmax/Boltzmann or top-N weighted random seeded by scores (as the Sims do), with a per-agent temperature. Add momentum (×1.1–1.25 on last choice) to prevent dithering.

For continuous decisions (auction bids, trade cash amounts), score a small set of discretized candidate amounts (e.g. mortgage value, 0.75×, 1.0×, 1.25× face value) and pick the best-scoring — this keeps the same scoring machinery. Baseline anchors: always bid at least mortgage value (half face) on anything, since a rival could otherwise mortgage it for instant profit; bid up properties an opponent needs to drain their cash.

### B. Phase detection and modulation

Detect phase from state, not turn count alone:
- **Early / acquisition:** few monopolies on board, many bank-owned properties, healthy cash. Priorities: buy aggressively, get out of jail fast, hold broad bargaining chips ("turn down nothing").
- **Mid / development:** first monopolies forming. Priorities: complete sets via trade, build to three houses ASAP, create the housing shortage, keep a build reserve.
- **Late / liquidity & survival:** most property owned, hotels appearing, players being eliminated. Priorities: hold a large cash buffer, stay in jail to dodge rent, mortgage low-value assets, finish off weak opponents, build hotels late to maximize pressure.

Encode phase as either (a) a multiplier table applied to action weights, or (b) phase-specific response curves (e.g. the LiquidityPreference S-curve shifts right late-game so the bot insists on a bigger reserve). The Bonjour et al. Monopoly environment formalizes pre-roll / out-of-turn / post-roll phases, useful for structuring *when* each action type is even evaluated.

### C. Personality vector

Suggested per-agent parameters, each in [0,1], sampled once at bot creation and stored:
- **Aggression** – boosts acquisition and opponent-denial weights, raises bid ceilings.
- **RiskTolerance** – lowers `cash_min` buffer and flattens BankruptcySafety curve.
- **MonopolyAppetite / Greed** – boosts GroupCompletion and development weights.
- **LiquidityPreference** – raises the reserve threshold and cash-holding utility.
- **TradeWillingness** – scales trade-proposal/acceptance weights (keep a floor > 0 so no bot refuses all trades).
- **Vindictiveness / Targeting** – weights OpponentDenial toward a specific rival (e.g. the leader or whoever last hurt this bot).

Sample sensibly: draw from bounded distributions (e.g. Beta or truncated normal centered at "competent") rather than uniform extremes, and define a few **archetypes** (e.g. "Slumlord" = high MonopolyAppetite on cheap sets + house-hoarding; "High Roller" = green/blue appetite, lower liquidity; "Trader" = high TradeWillingness; "Banker/Hoarder" = high LiquidityPreference, low aggression) then jitter each archetype's vector so five bots feel distinct but all competent. Hold the vector constant per game so behavior reads as a consistent "personality," not randomness.

### D. Trade valuation logic

Price every trade through the same utility function applied to the *resulting* state:
- Compute the bot's marginal utility gain (group completion, expected per-roll dollars) and subtract what it gives up.
- Price the opponent's "last missing piece" at a steep premium tied to the per-roll value of the set it completes for them — i.e. the holdout/leverage value, not face price.
- In 3+ player games, accept a slightly negative direct trade if `OpponentBurden` (the rent all other players will now pay) offsets it.
- Gate "give opponent a monopoly" trades on the opponent's cash: safe if they can't afford to build (they can't develop the set), dangerous if they're cash-rich.
- Always keep TradeWillingness floor > 0 to avoid stalemate bots.

### E. Software design and migration path

**Target structure (Java/Spring Boot):**
- `BotStrategy` interface with implementations `PureDomainBotDriver` (existing, kept as fallback) and `UtilityBotDriver` (new). Select via config/feature flag per bot or per match.
- `Planner` — a **pure function** `plan(GameState, BotMemory, BotParams) → Intent`. No side effects, no game mutation. This is where considerations, curves, compensation factor, weighting, and selection live. Trivially unit-testable: feed a constructed state, assert the chosen intent.
- `Executor` — translates an `Intent` (e.g. "propose trade X", "build on Tennessee") into the incremental game commands your engine already uses. Stateful, talks to the game/service layer.
- `BotConfig` (JSON) — externalized weights, response-curve parameters, phase multipliers, and personality vectors. Hot-reloadable for tuning. Validate on load.
- Considerations as small classes/functions each independently unit-tested with table-driven tests.

**Phased migration:**
1. Extract the `BotStrategy` interface; wrap existing logic as `PureDomainBotDriver` unchanged. No behavior change, safe.
2. Build the Planner skeleton + Executor, and implement ONE decision (e.g. buy-or-auction) in the utility model while delegating all others to the old driver. Shadow-run: log what the utility planner *would* do vs. the old logic.
3. Migrate decisions one at a time (build, mortgage, bid, trade, pay-debt), each behind the flag, comparing in self-play.
4. Externalize weights/curves to JSON; add the personality vector and phase modulation.
5. (Optional) Add a self-play harness that runs thousands of games and reports win-rate by config; tune weights by hand or with CMA-ES. Keep the old driver permanently as a baseline opponent and fallback.

## Recommendations

1. **Start now with the interface + Planner/Executor split and ONE migrated decision** (buy/auction), keeping `PureDomainBotDriver` as the default fallback. Benchmark: the utility version should match or beat the old driver's win-rate in ≥1,000 self-play games before you migrate the next decision.
2. **Use multiplicative combination with veto considerations + the compensation factor** for money decisions; reserve additive/weighted-sum only for sub-scores where compensation between factors is genuinely desired.
3. **Seed considerations with the published Monopoly numbers** (orange/red/light-blue priority, 3-house rule, per-roll expected-dollar table, ~one-lap cash reserve, phase-flipped jail logic). Hard-code as defaults, expose as JSON.
4. **Ship 5 archetype personality vectors with jitter**, held constant per game, and use softmax/top-N selection with per-agent temperature for human-like variety. Verify variety by logging decision distributions; verify competence by win-rate floor vs. a random bot (target ≥80%).
5. **Defer learning.** Hand-tune first. Only add CMA-ES self-play tuning if hand-tuning plateaus and you have the compute for thousands of simulated games — and even then, optimize the handful of weights/curve parameters, not a neural net.
6. **Thresholds that change the plan:** if utility bots stalemate (no trades, games never end), raise the TradeWillingness floor and OpponentBurden weight; if bots feel robotic, raise selection temperature; if bots go bankrupt often, raise `cash_min`/LiquidityPreference curve; if bots over-hoard cash, lower it. Tune one parameter at a time against self-play metrics.

## Caveats

- Many Monopoly strategy figures come from enthusiast sites and house-rule-laden contexts; the **Markov academic papers** (Illinois, Williams, MIT) and Truman Collins's simulation are the authoritative sources for landing probabilities and per-roll expected values, and small differences between sources stem from differing jail assumptions (pay-immediately vs. stay-3-turns) and house-rule variants. Treat the dollar tables as relative rankings, not exact truth for your specific ruleset.
- The IAUS compensation-factor formula is confirmed across independent implementer sources but the original primary source is the GDC Vault video (not a freely transcribed text); the formula itself is consistent across implementations.
- "Optimal" Monopoly play and "fun/human-like" play diverge — the research is clear that strictly optimal bots are less enjoyable, so the personality/sub-optimality layer is a feature, not a bug. But guard against *incompetent* sub-optimality (bankruptcy, never trading) with veto considerations and parameter floors.
- RL/CMA-ES results cited (e.g. the ~91.65% win-rate hybrid PPO) come from controlled setups against fixed-policy agents and don't transfer directly to your ruleset without retraining; they're evidence the approach works, not drop-in numbers.
- Auction and trade rules vary between the physical game, tournament rules, and video-game implementations (e.g. whether you can mortgage to cover a bid). Match your bot's assumptions to *your* engine's exact rules.