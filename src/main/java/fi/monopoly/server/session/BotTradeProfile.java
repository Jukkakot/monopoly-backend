package fi.monopoly.server.session;

/**
 * Preset trade negotiation postures for STRONG bots.
 * Controls how eagerly the bot accepts unfavourable trades and how
 * aggressively it counters.
 */
public enum BotTradeProfile {
    /** Demands near-fair value, counters conservatively. */
    CAUTIOUS(60, 200, -120),
    /** Balanced acceptance threshold with moderate counters. */
    BALANCED(20, 160, -140),
    /** Accepts mildly losing trades for strategic gain, counters boldly. */
    AGGRESSIVE(-20, 120, -180);

    /** Points of value shortfall the bot still accepts (higher = more generous). */
    private final int acceptThreshold;
    /** Max cash the bot adds when constructing a counter-offer. */
    private final int maxCounterAdjustment;
    /** Minimum counter-offer delta the bot will make (negative = bot can ask for less). */
    private final int counterOfferFloor;

    BotTradeProfile(int acceptThreshold, int maxCounterAdjustment, int counterOfferFloor) {
        this.acceptThreshold = acceptThreshold;
        this.maxCounterAdjustment = maxCounterAdjustment;
        this.counterOfferFloor = counterOfferFloor;
    }

    public int acceptThreshold() { return acceptThreshold; }
    public int maxCounterAdjustment() { return maxCounterAdjustment; }
    public int counterOfferFloor() { return counterOfferFloor; }
}
