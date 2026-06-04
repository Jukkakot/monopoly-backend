package fi.monopoly.server.session;

import fi.monopoly.types.StreetType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Tunable weights for the STRONG bot player.
 * All parameters have documented defaults that can be overridden to create
 * different playstyles and tournament-test them against each other.
 *
 * <p>Use {@link #defaults()} as a baseline and tweak individual fields
 * to experiment (e.g. via {@link #toBuilder()}).</p>
 */
public record StrongBotConfig(
        /** Baseline purchase score threshold (late game). Higher = pickier buyer. Default 6.5 */
        double buyThreshold,
        /** Minimum cash the bot keeps in normal conditions. Default 250 */
        int minCashReserve,
        /** Larger reserve when board is dangerous or late-game. Default 400 */
        int dangerCashReserve,
        /** Score bonus for completing a full color group. Default 9.0 */
        double completionWeight,
        /** Score bonus for partial group progress. Default 3.0 */
        double progressWeight,
        /** Score bonus for denying an opponent a near-complete set. Default 6.0 */
        double opponentBlockWeight,
        /** Flat score bonus for railroads. Default 2.5 */
        double railroadWeight,
        /** Flat score bonus for utilities. Default 0.5 */
        double utilityWeight,
        /** Penalty multiplier for ending up below reserve after purchase. Default 3.0 */
        double liquidityPenaltyWeight,
        /** Whether blocking opponent near-monopolies is enabled. Default true */
        boolean buyToBlockOpponent,
        /** Prefer reaching 3 houses before going further. Default true */
        boolean prioritizeThreeHouses,
        /** Prefer staying in jail late-game when board is dangerous. Default true */
        boolean preferJailLateGame,
        /** Multiplier for building score — higher = builds sooner. Default 1.0 */
        double houseBuildAggression,
        /** Penalty for pushing into hotel territory (level 4→5). Default 4.0 */
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
        /** Max building level bot will voluntarily push toward (1–5). Default 5 */
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

    public static StrongBotConfig defaults() {
        return new StrongBotConfig(
                6.5,   // buyThreshold
                250,   // minCashReserve
                400,   // dangerCashReserve
                9.0,   // completionWeight
                3.0,   // progressWeight
                6.0,   // opponentBlockWeight
                2.5,   // railroadWeight
                0.5,   // utilityWeight
                3.0,   // liquidityPenaltyWeight
                true,  // buyToBlockOpponent
                true,  // prioritizeThreeHouses
                true,  // preferJailLateGame
                1.0,   // houseBuildAggression
                4.0,   // hotelAversion
                2.0,   // developmentBias
                0.15,  // mortgageTolerance
                1.0,   // unmortgageAggression
                80,    // buildReservePerOpponentMonopoly
                1.0,   // auctionAggression
                15,    // tradeFairnessTolerance
                220,   // tradeSetCompletionWeight
                defaultColorGroupWeights(),
                500,   // jailExitThreshold
                1.0,   // bankruptcyAversion
                30,    // railroadCompletionWeight
                20,    // utilityCompletionWeight
                5,     // buildRoundCap
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
                4.5,   // buyThreshold — picks up more properties
                150,   // minCashReserve
                300,   // dangerCashReserve
                9.0, 3.5, 5.0, 3.0, 1.0,
                2.0,   // liquidityPenaltyWeight — less afraid of thin cash
                true, true, false,
                1.4,   // houseBuildAggression — builds sooner
                2.5,   // hotelAversion — happier to push to hotels
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
                8.0,   // buyThreshold — passes on mediocre buys
                350,   // minCashReserve
                550,   // dangerCashReserve
                9.5, 2.5, 7.0, 2.0, 0.3,
                4.5,   // liquidityPenaltyWeight — strongly penalises thin cash
                true, true, true,
                0.7,   // houseBuildAggression
                6.0,   // hotelAversion
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

    private static Map<StreetType, Double> defaultColorGroupWeights() {
        EnumMap<StreetType, Double> w = new EnumMap<>(StreetType.class);
        w.put(StreetType.BROWN,      0.95);
        w.put(StreetType.LIGHT_BLUE, 1.0);
        w.put(StreetType.PURPLE,     1.0);
        w.put(StreetType.ORANGE,     1.2);
        w.put(StreetType.RED,        1.15);
        w.put(StreetType.YELLOW,     1.05);
        w.put(StreetType.GREEN,      0.95);
        w.put(StreetType.DARK_BLUE,  1.05);
        w.put(StreetType.RAILROAD,   1.1);
        w.put(StreetType.UTILITY,    0.8);
        return Map.copyOf(w);
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

        public Builder buyThreshold(double v) { this.buyThreshold = v; return this; }
        public Builder minCashReserve(int v) { this.minCashReserve = v; return this; }
        public Builder dangerCashReserve(int v) { this.dangerCashReserve = v; return this; }
        public Builder auctionAggression(double v) { this.auctionAggression = v; return this; }
        public Builder houseBuildAggression(double v) { this.houseBuildAggression = v; return this; }
        public Builder buildRoundCap(int v) { this.buildRoundCap = v; return this; }
        public Builder colorGroupWeights(Map<StreetType, Double> v) { this.colorGroupWeights = v; return this; }

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
