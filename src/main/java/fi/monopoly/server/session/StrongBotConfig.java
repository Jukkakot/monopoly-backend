package fi.monopoly.server.session;

import fi.monopoly.types.StreetType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * Tunable weights for the STRONG bot player.
 *
 * <h3>Frozen vs tunable parameters</h3>
 * <p>Some parameters are set by well-established Monopoly theory and should not
 * be varied in evolutionary search:</p>
 * <ul>
 *   <li>{@code buyToBlockOpponent} = true — blocking near-monopolies is always correct</li>
 *   <li>{@code prioritizeThreeHouses} = true — 3 houses is the ROI sweet spot</li>
 *   <li>{@code preferJailLateGame} = true — jail is protection when board is developed</li>
 *   <li>{@code buildRoundCap} = 5 — max flexibility; hotelAversion handles the hotel decision</li>
 *   <li>{@code colorGroupWeights} — based on landing-probability research (see {@link #defaultColorGroupWeights()})</li>
 *   <li>{@code completionWeight} — set completion is the win condition, keep high</li>
 * </ul>
 *
 * <h3>Tunable parameters (used in {@link #mutate} / {@link #crossover})</h3>
 * <ol>
 *   <li>{@code buyThreshold} [4.0 – 9.0]</li>
 *   <li>{@code minCashReserve} [80 – 450]</li>
 *   <li>{@code dangerCashReserve} [200 – 700]</li>
 *   <li>{@code houseBuildAggression} [0.4 – 2.0]</li>
 *   <li>{@code hotelAversion} [1.5 – 10.0]</li>
 *   <li>{@code liquidityPenaltyWeight} [1.0 – 6.0]</li>
 *   <li>{@code opponentBlockWeight} [2.0 – 10.0]</li>
 *   <li>{@code railroadWeight} [1.5 – 5.5]</li>
 *   <li>{@code utilityWeight} [0.05 – 1.0]</li>
 *   <li>{@code auctionAggression} [0.5 – 1.5]</li>
 *   <li>{@code tradeFairnessTolerance} [-30 – 80]</li>
 *   <li>{@code buildReservePerOpponentMonopoly} [20 – 180]</li>
 * </ol>
 */
public record StrongBotConfig(
        /** Baseline purchase score threshold (late game). Higher = pickier buyer. Default 5.5 */
        double buyThreshold,
        /** Minimum cash the bot keeps in normal conditions. Default 200 */
        int minCashReserve,
        /** Larger reserve when board is dangerous or late-game. Default 400 */
        int dangerCashReserve,
        /** Score bonus for completing a full color group. Default 9.0 */
        double completionWeight,
        /** Score bonus for partial group progress. Default 3.0 */
        double progressWeight,
        /** Score bonus for denying an opponent a near-complete set. Default 6.0 */
        double opponentBlockWeight,
        /** Flat score bonus for railroads (underrated). Default 3.5 */
        double railroadWeight,
        /** Flat score bonus for utilities (weak investment). Default 0.3 */
        double utilityWeight,
        /** Penalty multiplier for ending up below reserve after purchase. Default 3.0 */
        double liquidityPenaltyWeight,
        /** Whether blocking opponent near-monopolies is enabled. Frozen: true */
        boolean buyToBlockOpponent,
        /** Prefer reaching 3 houses before going further. Frozen: true */
        boolean prioritizeThreeHouses,
        /** Prefer staying in jail late-game when board is dangerous. Frozen: true */
        boolean preferJailLateGame,
        /** Multiplier for building score — higher = builds sooner. Default 1.0 */
        double houseBuildAggression,
        /** Penalty for pushing into hotel territory (level 4→5). Default 5.5 */
        double hotelAversion,
        /** Score bonus for deepening already-owned monopolies. Default 2.0 */
        double developmentBias,
        /** Fraction of excess reserve pressure the bot tolerates. Default 0.15 */
        double mortgageTolerance,
        /** Multiplier for unmortgage score — higher = reactivates sooner. Default 1.0 */
        double unmortgageAggression,
        /** Extra cash reserve per threatening opponent monopoly. Default 80 */
        int buildReservePerOpponentMonopoly,
        /** Multiplier for auction bid ceiling. Default 1.0 */
        double auctionAggression,
        /** Points of unfairness tolerated for strategic trade upside. Default 15 */
        int tradeFairnessTolerance,
        /** Bonus for trades that complete or break a color monopoly. Default 220 */
        int tradeSetCompletionWeight,
        /** Per-group value multipliers for buy/build/trade/auction. */
        Map<StreetType, Double> colorGroupWeights,
        /** Danger score above which bot prefers staying jailed. Default 500 */
        int jailExitThreshold,
        /** Multiplier for willingness to liquidate assets near bankruptcy. Default 1.0 */
        double bankruptcyAversion,
        /** Extra bonus for gaining railroad synergy (2nd/3rd/4th). Default 30 */
        int railroadCompletionWeight,
        /** Extra bonus for gaining second utility. Default 20 */
        int utilityCompletionWeight,
        /** Max building level bot will voluntarily push toward (1–5). Frozen: 5 */
        int buildRoundCap,
        /** Extra cash buffer once bot owns at least one monopoly. Default 125 */
        int postMonopolyCashBuffer,
        /** Extra auction bonus when deed would complete a set. Default 90 */
        int auctionSetCompletionBonus,
        /** Multiplier for cash value inside trade evaluation. Default 1.0 */
        double tradeLiquidityWeight,
        /** Multiplier for denial/pressure against the leading opponent. Default 1.0 */
        double opponentLeaderPressure,
        /** Bias toward keeping jail card when board is dangerous. Default 1.0 */
        double jailCardHoldBias,
        /** Multiplier for how eagerly bot unmortgages high-quality mortgaged assets. Default 1.0 */
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

    public static StrongBotConfig defaults() {
        // Values calibrated by ablation study (25-config × 30 games/pair tournament):
        // dangerCash↓ (+6.2pp), railroadWeight↓ (+5.1pp), liquidityPenalty↓ (+3.2pp),
        // buildReservePerMonopoly↓ (+2.6pp) were the strongest single improvements.
        // buildAggression↑ is confirmed good, lower is a clear loser (−2.4pp).
        return new StrongBotConfig(
                5.5,   // buyThreshold
                175,   // minCashReserve (slightly lower — cash is better spent on properties)
                300,   // dangerCashReserve (ablation: 400 → 300 was strongest single gain)
                9.0,   // completionWeight
                3.0,   // progressWeight
                6.5,   // opponentBlockWeight (ablation: blockWeight+25% = slight winner)
                2.6,   // railroadWeight (ablation: lower railroad weight wins; 3.5 was too high)
                0.3,   // utilityWeight
                2.25,  // liquidityPenaltyWeight (ablation: lower penalty = less overly cautious)
                true,  // buyToBlockOpponent (frozen)
                true,  // prioritizeThreeHouses (frozen)
                true,  // preferJailLateGame (frozen)
                1.1,   // houseBuildAggression (ablation confirms: should not decrease)
                6.5,   // hotelAversion (ablation: hotelAversion+25% wins → stop at 3-4 houses)
                2.0,   // developmentBias
                0.18,  // mortgageTolerance
                1.0,   // unmortgageAggression
                60,    // buildReservePerOpponentMonopoly (ablation: 80 → 60 wins)
                1.0,   // auctionAggression
                15,    // tradeFairnessTolerance
                220,   // tradeSetCompletionWeight
                defaultColorGroupWeights(),
                500,   // jailExitThreshold
                1.0,   // bankruptcyAversion
                30,    // railroadCompletionWeight
                20,    // utilityCompletionWeight
                5,     // buildRoundCap (frozen)
                125,   // postMonopolyCashBuffer
                90,    // auctionSetCompletionBonus
                1.0,   // tradeLiquidityWeight
                1.0,   // opponentLeaderPressure
                1.0,   // jailCardHoldBias
                1.0    // mortgageRecoveryPriority
        );
    }

    /** Aggressive bot: buys and builds earlier, accepts riskier cash positions. */
    public static StrongBotConfig aggressive() {
        return new StrongBotConfig(
                4.0,   // buyThreshold — picks up more properties
                120,   // minCashReserve
                280,   // dangerCashReserve
                9.0, 3.5, 5.0,
                4.0,   // railroadWeight
                0.3,   // utilityWeight
                2.0,   // liquidityPenaltyWeight — less afraid of thin cash
                true, true, false,
                1.5,   // houseBuildAggression — builds sooner
                3.0,   // hotelAversion — happier to push to hotels
                3.0,   // developmentBias
                0.25,  // mortgageTolerance
                1.2,   // unmortgageAggression
                50,    // buildReservePerOpponentMonopoly
                1.3,   // auctionAggression
                30,    // tradeFairnessTolerance — more lenient on trades
                260,
                defaultColorGroupWeights(),
                700,   // jailExitThreshold — leaves jail more eagerly
                1.3,   // bankruptcyAversion
                50, 35, 5, 75, 120,
                0.9,   // tradeLiquidityWeight
                1.2,   // opponentLeaderPressure
                0.7,   // jailCardHoldBias — spends card more freely
                1.2    // mortgageRecoveryPriority
        );
    }

    /** Cautious bot: hoards cash, avoids risky positions, waits for clean trades. */
    public static StrongBotConfig cautious() {
        return new StrongBotConfig(
                7.5,   // buyThreshold — passes on mediocre buys
                320,   // minCashReserve
                520,   // dangerCashReserve
                9.5, 2.5, 7.0,
                3.5,   // railroadWeight
                0.2,   // utilityWeight
                4.5,   // liquidityPenaltyWeight — strongly penalises thin cash
                true, true, true,
                0.7,   // houseBuildAggression
                7.0,   // hotelAversion
                1.5,   // developmentBias
                0.05,  // mortgageTolerance
                0.8,   // unmortgageAggression
                110,   // buildReservePerOpponentMonopoly
                0.8,   // auctionAggression
                0,     // tradeFairnessTolerance — demands fair trades
                180,
                defaultColorGroupWeights(),
                350,   // jailExitThreshold — stays jailed when board is risky
                0.8,   // bankruptcyAversion
                20, 15, 4,
                175,   // postMonopolyCashBuffer
                60,    // auctionSetCompletionBonus
                1.2,   // tradeLiquidityWeight
                0.8,   // opponentLeaderPressure
                2.0,   // jailCardHoldBias — hoards jail card
                0.8    // mortgageRecoveryPriority
        );
    }

    /**
     * Optimised for 6-player games: properties vanish fast, trading is essential,
     * build pressure is needed to actually bankrupt opponents.
     *
     * <p>Key differences vs {@link #defaults()}:
     * <ul>
     *   <li>Much lower buy threshold — grab anything affordable immediately</li>
     *   <li>Lower cash reserves — 6 players pass Go often, cash replenishes faster</li>
     *   <li>Higher build aggression and lower hotel aversion — hotels are required
     *       to generate enough rent to force bankruptcies</li>
     *   <li>Higher trade fairness tolerance — completing a monopoly by trading
     *       at a slight loss is still the winning move</li>
     *   <li>Leave jail early — board scarcity means you must keep buying</li>
     * </ul>
     */
    public static StrongBotConfig sixPlayer() {
        return new StrongBotConfig(
                3.5,   // buyThreshold — grab almost everything (board fills up fast)
                110,   // minCashReserve — lower: 6 players = more Go income per round
                200,   // dangerCashReserve
                9.5,   // completionWeight
                4.0,   // progressWeight
                8.0,   // opponentBlockWeight — very competitive, block hard
                3.5,   // railroadWeight
                0.4,   // utilityWeight
                1.5,   // liquidityPenaltyWeight — less afraid of thin cash
                true,  // buyToBlockOpponent (frozen)
                true,  // prioritizeThreeHouses — still hit 3 first before hotels
                false, // preferJailLateGame — early game stay active to buy
                1.5,   // houseBuildAggression — build fast to create rent pressure
                3.5,   // hotelAversion — low: hotels needed to force bankruptcies
                3.0,   // developmentBias
                0.25,  // mortgageTolerance
                1.3,   // unmortgageAggression
                35,    // buildReservePerOpponentMonopoly — lower: less hoarding
                1.1,   // auctionAggression
                45,    // tradeFairnessTolerance — accept losing trades to complete monopoly
                300,   // tradeSetCompletionWeight
                defaultColorGroupWeights(),
                350,   // jailExitThreshold — leave jail sooner (board scarcity)
                1.1,   // bankruptcyAversion
                60, 40, 5, 80, 110,
                0.85,  // tradeLiquidityWeight — cash matters less than monopolies
                1.4,   // opponentLeaderPressure
                0.5,   // jailCardHoldBias — spend jail card freely
                1.3    // mortgageRecoveryPriority
        );
    }

    /**
     * Color-group weights derived from landing-probability research.
     *
     * <p>The spaces reachable most often after leaving Jail (dice sum peaking at 7)
     * are positions 6–9 from Jail: Orange group. The second Jail exit cluster hits Red.
     * Light Blue has the best rent/cost ratio early. Railroads give consistent passive income.
     * Utilities are unpredictable and low-yield.</p>
     */
    private static Map<StreetType, Double> defaultColorGroupWeights() {
        EnumMap<StreetType, Double> w = new EnumMap<>(StreetType.class);
        w.put(StreetType.ORANGE,     1.35);  // statistically best group (near jail, dice peak)
        w.put(StreetType.RED,        1.25);  // 2nd best — also near jail, 2nd circuit
        w.put(StreetType.LIGHT_BLUE, 1.15);  // best early ROI — cheap houses, good rents
        w.put(StreetType.RAILROAD,   1.20);  // consistent passive income, 4 = $200/stop
        w.put(StreetType.PURPLE,     1.05);  // decent, 3 properties
        w.put(StreetType.YELLOW,     1.00);  // solid mid-game
        w.put(StreetType.GREEN,      0.85);  // expensive houses, slow ROI
        w.put(StreetType.DARK_BLUE,  0.85);  // high variance, only 2 properties
        w.put(StreetType.BROWN,      0.70);  // very low rents, not worth much
        w.put(StreetType.UTILITY,    0.45);  // weakest investment
        return Map.copyOf(w);
    }

    // -------------------------------------------------------------------------
    // Evolutionary search: mutate and crossover
    // -------------------------------------------------------------------------

    /**
     * Returns a new config with one randomly chosen tunable parameter perturbed
     * by ±{@code strength} fraction (e.g. 0.2 = ±20 %).
     * Frozen parameters ({@code buyToBlockOpponent}, {@code prioritizeThreeHouses},
     * {@code preferJailLateGame}, {@code buildRoundCap}, {@code colorGroupWeights}) are never changed.
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
     * {@code this} or {@code other}. Frozen parameters come from {@code this}.
     */
    public StrongBotConfig crossover(StrongBotConfig other, Random rng) {
        return new StrongBotConfig(
                rng.nextBoolean() ? buyThreshold : other.buyThreshold,
                rng.nextBoolean() ? minCashReserve : other.minCashReserve,
                rng.nextBoolean() ? dangerCashReserve : other.dangerCashReserve,
                completionWeight,  // frozen-ish high value
                rng.nextBoolean() ? progressWeight : other.progressWeight,
                rng.nextBoolean() ? opponentBlockWeight : other.opponentBlockWeight,
                rng.nextBoolean() ? railroadWeight : other.railroadWeight,
                rng.nextBoolean() ? utilityWeight : other.utilityWeight,
                rng.nextBoolean() ? liquidityPenaltyWeight : other.liquidityPenaltyWeight,
                true,  // buyToBlockOpponent — frozen
                true,  // prioritizeThreeHouses — frozen
                true,  // preferJailLateGame — frozen
                rng.nextBoolean() ? houseBuildAggression : other.houseBuildAggression,
                rng.nextBoolean() ? hotelAversion : other.hotelAversion,
                rng.nextBoolean() ? developmentBias : other.developmentBias,
                rng.nextBoolean() ? mortgageTolerance : other.mortgageTolerance,
                rng.nextBoolean() ? unmortgageAggression : other.unmortgageAggression,
                rng.nextBoolean() ? buildReservePerOpponentMonopoly : other.buildReservePerOpponentMonopoly,
                rng.nextBoolean() ? auctionAggression : other.auctionAggression,
                rng.nextBoolean() ? tradeFairnessTolerance : other.tradeFairnessTolerance,
                tradeSetCompletionWeight,
                colorGroupWeights,  // frozen — theory-based
                jailExitThreshold,
                bankruptcyAversion,
                railroadCompletionWeight,
                utilityCompletionWeight,
                5,  // buildRoundCap — frozen
                rng.nextBoolean() ? postMonopolyCashBuffer : other.postMonopolyCashBuffer,
                rng.nextBoolean() ? auctionSetCompletionBonus : other.auctionSetCompletionBonus,
                tradeLiquidityWeight,
                opponentLeaderPressure,
                jailCardHoldBias,
                mortgageRecoveryPriority
        );
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // -------------------------------------------------------------------------
    // Builder helper for single-field overrides
    // -------------------------------------------------------------------------

    public Builder toBuilder() { return new Builder(this); }

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
        private boolean buyToBlockOpponent;
        private boolean prioritizeThreeHouses;
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

        private Builder(StrongBotConfig src) {
            this.buyThreshold = src.buyThreshold();
            this.minCashReserve = src.minCashReserve();
            this.dangerCashReserve = src.dangerCashReserve();
            this.completionWeight = src.completionWeight();
            this.progressWeight = src.progressWeight();
            this.opponentBlockWeight = src.opponentBlockWeight();
            this.railroadWeight = src.railroadWeight();
            this.utilityWeight = src.utilityWeight();
            this.liquidityPenaltyWeight = src.liquidityPenaltyWeight();
            this.buyToBlockOpponent = src.buyToBlockOpponent();
            this.prioritizeThreeHouses = src.prioritizeThreeHouses();
            this.preferJailLateGame = src.preferJailLateGame();
            this.houseBuildAggression = src.houseBuildAggression();
            this.hotelAversion = src.hotelAversion();
            this.developmentBias = src.developmentBias();
            this.mortgageTolerance = src.mortgageTolerance();
            this.unmortgageAggression = src.unmortgageAggression();
            this.buildReservePerOpponentMonopoly = src.buildReservePerOpponentMonopoly();
            this.auctionAggression = src.auctionAggression();
            this.tradeFairnessTolerance = src.tradeFairnessTolerance();
            this.tradeSetCompletionWeight = src.tradeSetCompletionWeight();
            this.colorGroupWeights = src.colorGroupWeights();
            this.jailExitThreshold = src.jailExitThreshold();
            this.bankruptcyAversion = src.bankruptcyAversion();
            this.railroadCompletionWeight = src.railroadCompletionWeight();
            this.utilityCompletionWeight = src.utilityCompletionWeight();
            this.buildRoundCap = src.buildRoundCap();
            this.postMonopolyCashBuffer = src.postMonopolyCashBuffer();
            this.auctionSetCompletionBonus = src.auctionSetCompletionBonus();
            this.tradeLiquidityWeight = src.tradeLiquidityWeight();
            this.opponentLeaderPressure = src.opponentLeaderPressure();
            this.jailCardHoldBias = src.jailCardHoldBias();
            this.mortgageRecoveryPriority = src.mortgageRecoveryPriority();
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
        public Builder houseBuildAggression(double v)              { this.houseBuildAggression = v; return this; }
        public Builder hotelAversion(double v)                     { this.hotelAversion = v; return this; }
        public Builder developmentBias(double v)                   { this.developmentBias = v; return this; }
        public Builder mortgageTolerance(double v)                 { this.mortgageTolerance = v; return this; }
        public Builder unmortgageAggression(double v)              { this.unmortgageAggression = v; return this; }
        public Builder buildReservePerOpponentMonopoly(int v)      { this.buildReservePerOpponentMonopoly = v; return this; }
        public Builder auctionAggression(double v)                 { this.auctionAggression = v; return this; }
        public Builder tradeFairnessTolerance(int v)               { this.tradeFairnessTolerance = v; return this; }
        public Builder buildRoundCap(int v)                        { this.buildRoundCap = v; return this; }
        public Builder colorGroupWeights(Map<StreetType, Double> v){ this.colorGroupWeights = v; return this; }

        public StrongBotConfig build() {
            return new StrongBotConfig(
                    buyThreshold, minCashReserve, dangerCashReserve,
                    completionWeight, progressWeight, opponentBlockWeight,
                    railroadWeight, utilityWeight, liquidityPenaltyWeight,
                    buyToBlockOpponent, prioritizeThreeHouses, preferJailLateGame,
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
