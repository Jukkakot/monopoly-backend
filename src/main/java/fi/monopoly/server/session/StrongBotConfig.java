package fi.monopoly.server.session;

import fi.monopoly.types.StreetType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * Tunable weights for the STRONG bot player.
 *
 * <h3>Hardwired parameters</h3>
 * <p>Two parameters are fixed by Monopoly theory and not exposed in the Builder:
 * {@code buyToBlockOpponent = true} (blocking near-monopolies is always correct) and
 * {@code prioritizeThreeHouses = true} (3 houses is the ROI sweet spot).
 *
 * <h3>Variable parameters</h3>
 * <p>{@code buildRoundCap} defaults to 5 but can be lowered (e.g. {@link #cautious()} uses 4
 * to avoid hotels). {@code preferJailLateGame} is {@code true} for conservative presets and
 * {@code false} when board pressure requires staying active (e.g. {@link #aggressive()},
 * {@link #sixPlayer()}).
 *
 * <h3>Runtime preset selection</h3>
 * <ul>
 *   <li>≤2 players → {@link #aggressive()}</li>
 *   <li>3 players  → {@link #sixPlayer()}</li>
 *   <li>4–6 players → {@link #defaults()}</li>
 * </ul>
 * Each bot seat receives a ±10 % mutation via {@link #forSeat}.
 * {@link #cautious()} is available as a diversity seed for evolutionary search.
 */
public record StrongBotConfig(
        /**
         * Purchase score gate — the bot only buys when buyScore() reaches this value.
         * <p>Higher = pickier; fewer properties bought, more cash retained.
         * Matters mostly in late-game (>10 unowned) — early game uses a lower hardwired
         * threshold (1.5 for streets) regardless of this setting.
         * Range: 4.0 (grab almost anything) … 9.0 (very selective).
         */
        double buyThreshold,

        /**
         * Normal cash floor. The bot won't spend below this in safe conditions.
         * <p>Higher = more conservative, less exposed to rents but slower to buy/build.
         * Affects every purchase, build, unmortgage, and auction bid.
         * Range: 80 … 450.
         */
        int minCashReserve,

        /**
         * Emergency cash floor, active when the board is dangerous (opponent monopolies, late game).
         * <p>When {@code boardDangerScore ≥ dangerCashReserve}, the bot switches from
         * {@code minCashReserve} to this higher floor, halting discretionary spending.
         * Higher = more defensive in threatening positions.
         * Range: 200 … 700.
         */
        int dangerCashReserve,

        /**
         * Buy-score bonus when the purchase would complete a full color monopoly.
         * <p>This is the single most powerful purchase signal — set-completing buys are
         * almost always worth it regardless of price. Rarely needs tuning.
         * Range: 7.0 … 12.0.
         */
        double completionWeight,

        /**
         * Buy-score bonus that scales with partial group progress.
         * <p>Score += progressWeight × (owned+1) / setSize. Encourages focusing on
         * a group where the bot already holds some deeds. Higher = more "finish what you started".
         * Range: 1.0 … 5.0.
         */
        double progressWeight,

        /**
         * Buy-score bonus applied when the purchase would deny an opponent their last needed deed
         * in a color group (i.e. opponent owns setSize−1 properties in that group).
         * <p>Higher = more defensive buying. Combines multiplicatively with
         * {@link #opponentLeaderPressure} when the opponent is the board leader.
         * Range: 2.0 … 10.0.
         */
        double opponentBlockWeight,

        /**
         * Base buy-score bonus for railroad deeds (stacks with completion synergy via
         * {@link #railroadCompletionWeight}).
         * <p>Higher = prioritises railroads over color groups of similar score.
         * Range: 1.5 … 5.5.
         */
        double railroadWeight,

        /**
         * Base buy-score bonus for utility deeds.
         * <p>Utilities have poor ROI (dice-dependent rent), so this is intentionally low.
         * Higher = buys utilities earlier; usually not worth tuning above 1.0.
         * Range: 0.05 … 1.0.
         */
        double utilityWeight,

        /**
         * Penalty multiplier for post-purchase cash falling below the dynamic reserve.
         * <p>Score -= liquidityPenaltyWeight × max(0, reserve − postCash) / 100.
         * Higher = bot is more reluctant to thin its cash; fewer "stretch" purchases.
         * Range: 1.0 … 6.0.
         */
        double liquidityPenaltyWeight,

        /**
         * Always {@code true} — hardwired in {@link Builder#build()}.
         * Enables the opponent-block bonus in {@code buyScore()}.
         * Not tunable; removing it weakens the bot.
         */
        boolean buyToBlockOpponent,

        /**
         * Always {@code true} — hardwired in {@link Builder#build()}.
         * Adds +4.0 to build score when the group average is below 3 houses,
         * capturing the well-known 3-house ROI sweet spot.
         * Not tunable.
         */
        boolean prioritizeThreeHouses,

        /**
         * <b>NOT YET WIRED INTO STRATEGY CODE.</b>
         * <p>Intended to make the bot prefer staying in jail when the board is dangerous.
         * Currently has no effect on bot decisions — kept as a future extension point.
         * Use {@link #jailExitThreshold} / {@link #dangerCashReserve} for risk aversion.
         */
        boolean preferJailLateGame,

        /**
         * Overall multiplier for the build score returned by {@code buildGroupScore()}.
         * <p>Higher = builds houses/hotels sooner, spending cash faster to create rent pressure.
         * This is the most impactful "aggression" knob after buyThreshold.
         * Range: 0.4 (passive builder) … 2.0 (aggressive builder).
         */
        double houseBuildAggression,

        /**
         * Score penalty subtracted from {@code buildGroupScore()} when the next build step
         * would push any property in the group to hotel level (4 → 5).
         * <p>Higher = bot caps itself at 4 houses per property; lower = builds hotels freely.
         * {@link #buildRoundCap} offers a hard cap instead of a soft penalty.
         * Range: 1.5 … 10.0.
         */
        double hotelAversion,

        /**
         * Buy/build/unmortgage score bonus for properties in groups the bot already owns.
         * <p>Encourages the bot to deepen existing holdings rather than scatter across groups.
         * Applies in {@code buyScore()}, {@code buildGroupScore()}, and {@code unmortgageScore()}.
         * Range: 1.0 … 5.0.
         */
        double developmentBias,

        /**
         * Fraction of the excess reserve pressure that is discounted when deciding whether
         * to spend (buy, build, unmortgage).
         * <p>Effective reserve = max(minCash, raw − raw×mortgageTolerance). Higher tolerance
         * lets the bot act despite a temporarily inflated reserve estimate.
         * Range: 0.0 (no discount) … 0.5 (can ignore up to half the danger premium).
         */
        double mortgageTolerance,

        /**
         * Multiplier on {@code unmortgageScore()}.
         * <p>Higher = the bot reactivates mortgaged deeds sooner after a cash crunch.
         * Compounds with {@link #mortgageRecoveryPriority}.
         * Range: 0.5 … 2.0.
         */
        double unmortgageAggression,

        /**
         * Extra cash reserved per active opponent monopoly (i.e. opponent owns a full color group).
         * <p>Adds directly to the dynamic reserve: each opponent monopoly raises the bot's spending
         * floor by this amount, protecting against catastrophic rents.
         * Range: 20 … 180.
         */
        int buildReservePerOpponentMonopoly,

        /**
         * Auction ceiling multiplier: bot bids up to {@code facePrice × auctionAggression}.
         * <p>Higher = willing to overpay at auction; lower = lets properties go cheap.
         * The ceiling is lifted further by {@link #auctionSetCompletionBonus} for monopoly-completing deeds.
         * Range: 0.5 … 1.5.
         */
        double auctionAggression,

        /**
         * How many value-points of disadvantage the bot tolerates in an incoming trade offer.
         * <p>Trade is accepted when: valueReceived ≥ valueGiven − tradeFairnessTolerance.
         * Higher = more generous; the bot will accept slightly losing trades to gain strategic position.
         * Range: −30 (demands overpayment) … 80 (very loose).
         */
        int tradeFairnessTolerance,

        /**
         * Bonus value added to a property's trade worth when receiving it would complete a monopoly.
         * <p>Makes the bot willing to overpay significantly for a set-completing deed in trades.
         * Stacks with {@link #tradeFairnessTolerance}.
         * Range: 100 … 400.
         */
        int tradeSetCompletionWeight,

        /**
         * Per-group buy/build/trade/auction score multipliers derived from landing probability.
         * <p>Orange (1.35) and Red (1.25) are statistically the most visited groups after jail.
         * Light Blue (1.15) has the best early rent/cost ratio.
         * These are set globally in {@link #defaultColorGroupWeights()} and rarely need tuning
         * unless you have new landing-frequency data.
         */
        Map<StreetType, Double> colorGroupWeights,

        /**
         * <b>NOT YET WIRED INTO STRATEGY CODE.</b>
         * <p>Intended as a board-danger threshold above which the bot uses its roll-to-exit right
         * or jail card. Currently has no effect on bot decisions.
         * Kept as a future extension point.
         */
        int jailExitThreshold,

        /**
         * <b>NOT YET WIRED INTO STRATEGY CODE.</b>
         * <p>Intended to control willingness to sell assets in debt situations.
         * Currently has no effect. Kept as a future extension point.
         */
        double bankruptcyAversion,

        /**
         * Extra buy-score bonus per railroad already owned, applied when buying another railroad.
         * <p>Score += ownedInSet × railroadCompletionWeight / 10. Incentivises completing
         * the railroad monopoly (4 railroads = €200/stop). Higher = hoards railroads.
         * Range: 0 … 80.
         */
        int railroadCompletionWeight,

        /**
         * Extra buy-score bonus when buying the second utility (completing the utility set).
         * <p>The second utility changes rent from dice×4 to dice×10, making the set
         * significantly more valuable. This bonus captures that step-change.
         * Range: 0 … 60.
         */
        int utilityCompletionWeight,

        /**
         * Hard cap on building level the bot will voluntarily reach (1–5, where 5 = hotel).
         * <p>Set to 4 to prevent hotel building entirely. Operates independently of
         * {@link #hotelAversion} (soft penalty) — this is a hard stop in {@code dispatchEndTurn()}.
         * Range: 1 … 5.
         */
        int buildRoundCap,

        /**
         * Extra cash buffer added to the dynamic reserve once the bot owns at least one monopoly.
         * <p>Owning a monopoly makes the bot a target — this padding ensures it can weather
         * retaliation while still building. Higher = more conservative post-monopoly spending.
         * Range: 0 … 300.
         */
        int postMonopolyCashBuffer,

        /**
         * Extra bid ceiling added to an auction when winning the deed would complete a color monopoly.
         * <p>Added directly to the ceiling computed by {@code facePrice × auctionAggression}.
         * Ensures the bot fights hard for set-completing properties even if they go above face value.
         * Range: 0 … 200.
         */
        int auctionSetCompletionBonus,

        /**
         * Multiplier for the cash component when evaluating a trade offer.
         * <p>In {@code evaluateTradeSelection()}: value += moneyAmount × tradeLiquidityWeight.
         * Higher = values cash more than property in trades; lower = more willing to trade cash for deeds.
         * Range: 0.5 … 2.0.
         */
        double tradeLiquidityWeight,

        /**
         * Multiplier applied to {@link #opponentBlockWeight} when the opponent being blocked
         * is the board leader (highest net worth).
         * <p>Higher = bot focuses denial specifically on the strongest opponent.
         * Range: 0.5 … 2.0.
         */
        double opponentLeaderPressure,

        /**
         * <b>NOT YET WIRED INTO STRATEGY CODE.</b>
         * <p>Intended to bias the bot toward holding get-out-of-jail cards when the board is dangerous.
         * Currently has no effect on bot decisions. Kept as a future extension point.
         */
        double jailCardHoldBias,

        /**
         * Secondary multiplier on {@code unmortgageScore()}, stacking with {@link #unmortgageAggression}.
         * <p>Separates the general eagerness to unmortgage ({@code unmortgageAggression}) from
         * a per-property recovery priority tuned for high-quality assets (monopoly groups).
         * Range: 0.5 … 2.0.
         */
        double mortgageRecoveryPriority
) {
    public StrongBotConfig {
        colorGroupWeights = Map.copyOf(colorGroupWeights);
    }

    public double colorGroupWeight(StreetType streetType) {
        return colorGroupWeights.getOrDefault(streetType, 1.0);
    }

    // -------------------------------------------------------------------------
    // Named presets
    // -------------------------------------------------------------------------

    /**
     * Balanced config optimised for 4–6 players by evolutionary search
     * (15 generations, 19 800 games). This is the baseline all other presets are built from.
     */
    public static StrongBotConfig defaults() {
        return new Builder()
                .buyThreshold(4.0)
                .minCashReserve(120)
                .dangerCashReserve(280)
                .completionWeight(9.0)
                .progressWeight(3.0)
                .opponentBlockWeight(4.73)
                .railroadWeight(5.16)
                .utilityWeight(0.28)
                .liquidityPenaltyWeight(1.71)
                .preferJailLateGame(true)
                .houseBuildAggression(1.38)
                .hotelAversion(6.53)
                .developmentBias(2.5)
                .mortgageTolerance(0.25)
                .unmortgageAggression(1.0)
                .buildReservePerOpponentMonopoly(44)
                .auctionAggression(1.30)
                .tradeFairnessTolerance(30)
                .tradeSetCompletionWeight(220)
                .jailExitThreshold(500)
                .bankruptcyAversion(1.0)
                .railroadCompletionWeight(30)
                .utilityCompletionWeight(20)
                .buildRoundCap(5)
                .postMonopolyCashBuffer(75)
                .auctionSetCompletionBonus(90)
                .tradeLiquidityWeight(1.0)
                .opponentLeaderPressure(1.0)
                .jailCardHoldBias(1.0)
                .mortgageRecoveryPriority(1.0)
                .build();
    }

    /**
     * Optimal for 2-player games (~65 % win rate vs {@link #defaults()}).
     * Buys and builds earlier; accepts thinner cash positions; leaves jail eagerly.
     */
    public static StrongBotConfig aggressive() {
        return new Builder()
                .buyThreshold                          (4)
                .minCashReserve                        (120)
                .dangerCashReserve                     (467)  // was 280,000 (+66,8%)
                .completionWeight                      (9.5000)  // was 9,000 (+5,6%)
                .progressWeight                        (3)
                .opponentBlockWeight                   (7.8166)  // was 4,730 (+65,3%)
                .railroadWeight                        (5.5000)  // was 5,160 (+6,6%)
                .utilityWeight                         (0.3393)  // was 0,280 (+21,2%)
                .liquidityPenaltyWeight                (1.7100)
                .opponentLeaderPressure                (0.8000)  // was 1,000 (-20,0%)
                .railroadCompletionWeight              (20)  // was 30,000 (-33,3%)
                .utilityCompletionWeight               (15)  // was 20,000 (-25,0%)
                .houseBuildAggression                  (1.3800)
                .hotelAversion                         (6.5300)
                .developmentBias                       (2.5000)
                .buildRoundCap                         (4)  // was 5,000 (-20,0%)
                .buildReservePerOpponentMonopoly       (44)
                .postMonopolyCashBuffer                (75)
                .mortgageTolerance                     (0.2500)
                .unmortgageAggression                  (1)
                .mortgageRecoveryPriority              (0.8000)  // was 1,000 (-20,0%)
                .auctionAggression                     (1.0749)  // was 1,300 (-17,3%)
                .auctionSetCompletionBonus             (90)
                .tradeFairnessTolerance                (0)  // was 30,000 (-100,0%)
                .tradeSetCompletionWeight              (180)  // was 220,000 (-18,2%)
                .tradeLiquidityWeight                  (1.2000)  // was 1,000 (+20,0%)
                .preferJailLateGame                    (true)
                .build();
    }

    /**
     * Conservative bot: hoards cash, avoids risky positions, demands fair trades.
     * Useful as a diversity seed in evolutionary search; not used in normal gameplay.
     */
    public static StrongBotConfig cautious() {
        return new Builder()
                .buyThreshold(7.5)                // passes on mediocre buys
                .minCashReserve(320)
                .dangerCashReserve(520)
                .completionWeight(9.5)
                .progressWeight(2.5)
                .opponentBlockWeight(7.0)
                .railroadWeight(3.5)
                .utilityWeight(0.2)
                .liquidityPenaltyWeight(4.5)       // strongly penalises thin cash
                .preferJailLateGame(true)
                .houseBuildAggression(0.7)
                .hotelAversion(7.0)
                .developmentBias(1.5)
                .mortgageTolerance(0.05)
                .unmortgageAggression(0.8)
                .buildReservePerOpponentMonopoly(110)
                .auctionAggression(0.8)
                .tradeFairnessTolerance(0)         // demands fair trades
                .tradeSetCompletionWeight(180)
                .jailExitThreshold(350)            // stays jailed when board is risky
                .bankruptcyAversion(0.8)
                .railroadCompletionWeight(20)
                .utilityCompletionWeight(15)
                .buildRoundCap(4)                  // avoids hotels entirely
                .postMonopolyCashBuffer(175)
                .auctionSetCompletionBonus(60)
                .tradeLiquidityWeight(1.2)
                .opponentLeaderPressure(0.8)
                .jailCardHoldBias(2.0)             // hoards jail card
                .mortgageRecoveryPriority(0.8)
                .build();
    }

    /**
     * Optimised for 3-player games (also effective for 5–6 players).
     * Properties vanish fast: grab almost everything, build aggressively, trade for monopolies.
     *
     * <p>Key differences vs {@link #defaults()}:
     * <ul>
     *   <li>Lower buy threshold — grab almost anything affordable immediately</li>
     *   <li>Lower cash reserves — more players pass Go per round, cash replenishes faster</li>
     *   <li>Higher build aggression and lower hotel aversion — hotels required to force bankruptcies</li>
     *   <li>Higher trade fairness tolerance — completing a monopoly at a slight loss is still a win</li>
     *   <li>Leave jail early — board scarcity means staying active matters</li>
     * </ul>
     */
    public static StrongBotConfig sixPlayer() {
        return new Builder()
                .buyThreshold(3.5)                 // grab almost everything
                .minCashReserve(110)               // lower: more players = more Go income per round
                .dangerCashReserve(200)
                .completionWeight(9.5)
                .progressWeight(4.0)
                .opponentBlockWeight(8.0)           // very competitive, block hard
                .railroadWeight(3.5)
                .utilityWeight(0.4)
                .liquidityPenaltyWeight(1.5)        // less afraid of thin cash
                .preferJailLateGame(false)          // board scarcity — must keep buying
                .houseBuildAggression(1.5)          // build fast to create rent pressure
                .hotelAversion(3.5)                // hotels needed to force bankruptcies
                .developmentBias(3.0)
                .mortgageTolerance(0.25)
                .unmortgageAggression(1.3)
                .buildReservePerOpponentMonopoly(35)
                .auctionAggression(1.1)
                .tradeFairnessTolerance(45)         // accept losing trades to complete monopoly
                .tradeSetCompletionWeight(300)
                .jailExitThreshold(350)             // leave jail sooner — board scarcity
                .bankruptcyAversion(1.1)
                .railroadCompletionWeight(60)
                .utilityCompletionWeight(40)
                .buildRoundCap(5)
                .postMonopolyCashBuffer(80)
                .auctionSetCompletionBonus(110)
                .tradeLiquidityWeight(0.85)         // cash matters less than monopolies
                .opponentLeaderPressure(1.4)
                .jailCardHoldBias(0.5)              // spend jail card freely
                .mortgageRecoveryPriority(1.3)
                .build();
    }

    /**
     * Color-group weights derived from landing-probability research.
     *
     * <p>The spaces reachable most often after leaving Jail (dice sum peaking at 7)
     * are positions 6–9 from Jail: Orange group. The second Jail exit cluster hits Red.
     * Light Blue has the best rent/cost ratio early. Railroads give consistent passive income.</p>
     */
    static Map<StreetType, Double> defaultColorGroupWeights() {
        EnumMap<StreetType, Double> w = new EnumMap<>(StreetType.class);
        w.put(StreetType.ORANGE,     1.35);  // statistically best group (near jail, dice peak)
        w.put(StreetType.RED,        1.25);  // 2nd best — also near jail, 2nd circuit
        w.put(StreetType.LIGHT_BLUE, 1.15);  // best early ROI — cheap houses, good rents
        w.put(StreetType.RAILROAD,   1.20);  // consistent passive income, 4 = €200/stop
        w.put(StreetType.PURPLE,     1.05);  // decent, 3 properties
        w.put(StreetType.YELLOW,     1.00);  // solid mid-game
        w.put(StreetType.GREEN,      0.85);  // expensive houses, slow ROI
        w.put(StreetType.DARK_BLUE,  0.85);  // high variance, only 2 properties
        w.put(StreetType.BROWN,      0.70);  // very low rents, not worth much
        w.put(StreetType.UTILITY,    0.45);  // weakest investment
        return Map.copyOf(w);
    }

    // -------------------------------------------------------------------------
    // Player-count aware config selection
    // -------------------------------------------------------------------------

    /** Returns the empirically best preset for the given number of players. */
    public static StrongBotConfig forPlayerCount(int playerCount) {
        if (playerCount <= 2) return aggressive();  // aggressive wins ~65% vs defaults in 2p
        if (playerCount == 3) return sixPlayer();   // sixPlayer wins ~38% vs 33% expected in 3p
        return defaults();                          // defaults wins 4–6 player
    }

    /**
     * Returns a config for a specific bot seat with seat-level diversity.
     *
     * <p>Seat 0 receives the optimal config for the player count. Other seats get
     * ±10 % mutations seeded by {@code seed} (e.g. {@code sessionId.hashCode() ^ seatIndex})
     * so each bot in the same game has a distinct but competitive playstyle.
     *
     * @param seatIndex        0-based index among bot seats in this session
     * @param totalPlayerCount total players (human + bot)
     * @param seed             deterministic per-seat seed
     */
    public static StrongBotConfig forSeat(int seatIndex, int totalPlayerCount, long seed) {
        StrongBotConfig base = forPlayerCount(totalPlayerCount);
        if (seatIndex == 0) return base;
        return base.mutate(new Random(seed), 0.10);
    }

    // -------------------------------------------------------------------------
    // Evolutionary search: mutate and crossover
    // -------------------------------------------------------------------------

    /**
     * Returns a new config with one randomly chosen tunable parameter perturbed
     * by ±{@code strength} fraction (e.g. 0.2 = ±20 %).
     * The 12 tunable parameters are: buyThreshold, minCashReserve, dangerCashReserve,
     * houseBuildAggression, hotelAversion, liquidityPenaltyWeight, opponentBlockWeight,
     * railroadWeight, utilityWeight, auctionAggression, tradeFairnessTolerance,
     * buildReservePerOpponentMonopoly.
     */
    public StrongBotConfig mutate(Random rng, double strength) {
        int param = rng.nextInt(12);
        double d = 1.0 + (rng.nextDouble() * 2 - 1) * strength;
        Builder b = toBuilder();
        return switch (param) {
            case 0  -> b.buyThreshold(clamp(buyThreshold * d, 4.0, 9.0)).build();
            case 1  -> b.minCashReserve(clampInt((int)(minCashReserve * d), 80, 450)).build();
            case 2  -> b.dangerCashReserve(clampInt((int)(dangerCashReserve * d), 200, 700)).build();
            case 3  -> b.houseBuildAggression(clamp(houseBuildAggression * d, 0.4, 2.0)).build();
            case 4  -> b.hotelAversion(clamp(hotelAversion * d, 1.5, 10.0)).build();
            case 5  -> b.liquidityPenaltyWeight(clamp(liquidityPenaltyWeight * d, 1.0, 6.0)).build();
            case 6  -> b.opponentBlockWeight(clamp(opponentBlockWeight * d, 2.0, 10.0)).build();
            case 7  -> b.railroadWeight(clamp(railroadWeight * d, 1.5, 5.5)).build();
            case 8  -> b.utilityWeight(clamp(utilityWeight * d, 0.05, 1.0)).build();
            case 9  -> b.auctionAggression(clamp(auctionAggression * d, 0.5, 1.5)).build();
            case 10 -> b.tradeFairnessTolerance(clampInt((int)(tradeFairnessTolerance * d), -30, 80)).build();
            case 11 -> b.buildReservePerOpponentMonopoly(clampInt((int)(buildReservePerOpponentMonopoly * d), 20, 180)).build();
            default -> this;
        };
    }

    /**
     * Creates a child config by randomly picking each tunable parameter from either
     * {@code this} or {@code other}. Non-crossed parameters come from {@code this}.
     */
    public StrongBotConfig crossover(StrongBotConfig other, Random rng) {
        return toBuilder()
                .buyThreshold(rng.nextBoolean() ? buyThreshold : other.buyThreshold)
                .minCashReserve(rng.nextBoolean() ? minCashReserve : other.minCashReserve)
                .dangerCashReserve(rng.nextBoolean() ? dangerCashReserve : other.dangerCashReserve)
                .progressWeight(rng.nextBoolean() ? progressWeight : other.progressWeight)
                .opponentBlockWeight(rng.nextBoolean() ? opponentBlockWeight : other.opponentBlockWeight)
                .railroadWeight(rng.nextBoolean() ? railroadWeight : other.railroadWeight)
                .utilityWeight(rng.nextBoolean() ? utilityWeight : other.utilityWeight)
                .liquidityPenaltyWeight(rng.nextBoolean() ? liquidityPenaltyWeight : other.liquidityPenaltyWeight)
                .houseBuildAggression(rng.nextBoolean() ? houseBuildAggression : other.houseBuildAggression)
                .hotelAversion(rng.nextBoolean() ? hotelAversion : other.hotelAversion)
                .developmentBias(rng.nextBoolean() ? developmentBias : other.developmentBias)
                .mortgageTolerance(rng.nextBoolean() ? mortgageTolerance : other.mortgageTolerance)
                .unmortgageAggression(rng.nextBoolean() ? unmortgageAggression : other.unmortgageAggression)
                .buildReservePerOpponentMonopoly(rng.nextBoolean() ? buildReservePerOpponentMonopoly : other.buildReservePerOpponentMonopoly)
                .auctionAggression(rng.nextBoolean() ? auctionAggression : other.auctionAggression)
                .tradeFairnessTolerance(rng.nextBoolean() ? tradeFairnessTolerance : other.tradeFairnessTolerance)
                .postMonopolyCashBuffer(rng.nextBoolean() ? postMonopolyCashBuffer : other.postMonopolyCashBuffer)
                .auctionSetCompletionBonus(rng.nextBoolean() ? auctionSetCompletionBonus : other.auctionSetCompletionBonus)
                .build();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Create a builder starting from {@link #defaults()}.
     * Useful for creating a config that overrides only specific parameters:
     * <pre>
     *   var config = StrongBotConfig.fromDefaults()
     *     .hotelAversion(6.0)
     *     .liquidityPenaltyWeight(1.8)
     *     .build();
     * </pre>
     */
    public static Builder fromDefaults() {
        return new Builder(defaults());
    }

    /** Convert this config to a builder for modification. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private double buyThreshold;
        private int minCashReserve;
        private int dangerCashReserve;
        private double completionWeight;
        private double progressWeight;
        private double opponentBlockWeight;
        private double railroadWeight;
        private double utilityWeight;
        private double liquidityPenaltyWeight;
        private boolean preferJailLateGame;
        private double houseBuildAggression;
        private double hotelAversion;
        private double developmentBias;
        private double mortgageTolerance;
        private double unmortgageAggression;
        private int buildReservePerOpponentMonopoly;
        private double auctionAggression;
        private int tradeFairnessTolerance;
        private int tradeSetCompletionWeight;
        private Map<StreetType, Double> colorGroupWeights;
        private int jailExitThreshold;
        private double bankruptcyAversion;
        private int railroadCompletionWeight;
        private int utilityCompletionWeight;
        private int buildRoundCap;
        private int postMonopolyCashBuffer;
        private int auctionSetCompletionBonus;
        private double tradeLiquidityWeight;
        private double opponentLeaderPressure;
        private double jailCardHoldBias;
        private double mortgageRecoveryPriority;

        /** No-arg constructor: initialises {@code colorGroupWeights} to the theory-based defaults. */
        public Builder() {
            this.colorGroupWeights = defaultColorGroupWeights();
        }

        private Builder(StrongBotConfig src) {
            this.buyThreshold                    = src.buyThreshold();
            this.minCashReserve                  = src.minCashReserve();
            this.dangerCashReserve               = src.dangerCashReserve();
            this.completionWeight                = src.completionWeight();
            this.progressWeight                  = src.progressWeight();
            this.opponentBlockWeight             = src.opponentBlockWeight();
            this.railroadWeight                  = src.railroadWeight();
            this.utilityWeight                   = src.utilityWeight();
            this.liquidityPenaltyWeight          = src.liquidityPenaltyWeight();
            this.preferJailLateGame              = src.preferJailLateGame();
            this.houseBuildAggression            = src.houseBuildAggression();
            this.hotelAversion                   = src.hotelAversion();
            this.developmentBias                 = src.developmentBias();
            this.mortgageTolerance               = src.mortgageTolerance();
            this.unmortgageAggression            = src.unmortgageAggression();
            this.buildReservePerOpponentMonopoly = src.buildReservePerOpponentMonopoly();
            this.auctionAggression               = src.auctionAggression();
            this.tradeFairnessTolerance          = src.tradeFairnessTolerance();
            this.tradeSetCompletionWeight        = src.tradeSetCompletionWeight();
            this.colorGroupWeights               = src.colorGroupWeights();
            this.jailExitThreshold               = src.jailExitThreshold();
            this.bankruptcyAversion              = src.bankruptcyAversion();
            this.railroadCompletionWeight        = src.railroadCompletionWeight();
            this.utilityCompletionWeight         = src.utilityCompletionWeight();
            this.buildRoundCap                   = src.buildRoundCap();
            this.postMonopolyCashBuffer          = src.postMonopolyCashBuffer();
            this.auctionSetCompletionBonus       = src.auctionSetCompletionBonus();
            this.tradeLiquidityWeight            = src.tradeLiquidityWeight();
            this.opponentLeaderPressure          = src.opponentLeaderPressure();
            this.jailCardHoldBias                = src.jailCardHoldBias();
            this.mortgageRecoveryPriority        = src.mortgageRecoveryPriority();
        }

        public Builder buyThreshold(double v)                      { this.buyThreshold = v; return this; }
        public Builder minCashReserve(int v)                       { this.minCashReserve = v; return this; }
        public Builder dangerCashReserve(int v)                    { this.dangerCashReserve = v; return this; }
        public Builder completionWeight(double v)                  { this.completionWeight = v; return this; }
        public Builder progressWeight(double v)                    { this.progressWeight = v; return this; }
        public Builder opponentBlockWeight(double v)               { this.opponentBlockWeight = v; return this; }
        public Builder railroadWeight(double v)                    { this.railroadWeight = v; return this; }
        public Builder utilityWeight(double v)                     { this.utilityWeight = v; return this; }
        public Builder liquidityPenaltyWeight(double v)            { this.liquidityPenaltyWeight = v; return this; }
        public Builder preferJailLateGame(boolean v)               { this.preferJailLateGame = v; return this; }
        public Builder houseBuildAggression(double v)              { this.houseBuildAggression = v; return this; }
        public Builder hotelAversion(double v)                     { this.hotelAversion = v; return this; }
        public Builder developmentBias(double v)                   { this.developmentBias = v; return this; }
        public Builder mortgageTolerance(double v)                 { this.mortgageTolerance = v; return this; }
        public Builder unmortgageAggression(double v)              { this.unmortgageAggression = v; return this; }
        public Builder buildReservePerOpponentMonopoly(int v)      { this.buildReservePerOpponentMonopoly = v; return this; }
        public Builder auctionAggression(double v)                 { this.auctionAggression = v; return this; }
        public Builder tradeFairnessTolerance(int v)               { this.tradeFairnessTolerance = v; return this; }
        public Builder tradeSetCompletionWeight(int v)             { this.tradeSetCompletionWeight = v; return this; }
        public Builder colorGroupWeights(Map<StreetType, Double> v){ this.colorGroupWeights = v; return this; }
        public Builder jailExitThreshold(int v)                    { this.jailExitThreshold = v; return this; }
        public Builder bankruptcyAversion(double v)                { this.bankruptcyAversion = v; return this; }
        public Builder railroadCompletionWeight(int v)             { this.railroadCompletionWeight = v; return this; }
        public Builder utilityCompletionWeight(int v)              { this.utilityCompletionWeight = v; return this; }
        public Builder buildRoundCap(int v)                        { this.buildRoundCap = v; return this; }
        public Builder postMonopolyCashBuffer(int v)               { this.postMonopolyCashBuffer = v; return this; }
        public Builder auctionSetCompletionBonus(int v)            { this.auctionSetCompletionBonus = v; return this; }
        public Builder tradeLiquidityWeight(double v)              { this.tradeLiquidityWeight = v; return this; }
        public Builder opponentLeaderPressure(double v)            { this.opponentLeaderPressure = v; return this; }
        public Builder jailCardHoldBias(double v)                  { this.jailCardHoldBias = v; return this; }
        public Builder mortgageRecoveryPriority(double v)          { this.mortgageRecoveryPriority = v; return this; }

        public StrongBotConfig build() {
            return new StrongBotConfig(
                    buyThreshold, minCashReserve, dangerCashReserve,
                    completionWeight, progressWeight, opponentBlockWeight,
                    railroadWeight, utilityWeight, liquidityPenaltyWeight,
                    true,  // buyToBlockOpponent — always true
                    true,  // prioritizeThreeHouses — always true
                    preferJailLateGame,
                    houseBuildAggression, hotelAversion, developmentBias,
                    mortgageTolerance, unmortgageAggression, buildReservePerOpponentMonopoly,
                    auctionAggression, tradeFairnessTolerance, tradeSetCompletionWeight,
                    colorGroupWeights, jailExitThreshold, bankruptcyAversion,
                    railroadCompletionWeight, utilityCompletionWeight, buildRoundCap,
                    postMonopolyCashBuffer, auctionSetCompletionBonus,
                    tradeLiquidityWeight, opponentLeaderPressure, jailCardHoldBias,
                    mortgageRecoveryPriority
            );
        }
    }
}
