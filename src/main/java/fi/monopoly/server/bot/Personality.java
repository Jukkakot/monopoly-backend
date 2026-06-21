package fi.monopoly.server.bot;

import fi.monopoly.utils.RandomSource;

/**
 * Per-bot trait vector — sampled once at game start from an archetype + jitter,
 * then held constant for the whole game.
 *
 * <p>All fields are in [0,1]. They multiply or shift per-consideration weights to
 * produce an agent that is aggressive or cautious, trade-willing or hoarding, etc.
 * Personality is a bias layer on top of {@link BotParams}; it is NOT re-rolled
 * per decision (that would produce incoherent behaviour).</p>
 */
public record Personality(
        double aggression,           // 0=passive, 1=very aggressive (buying, bidding)
        double riskTolerance,        // 0=conservative reserve, 1=thin reserve OK
        double monopolyAppetite,     // 0=indifferent to color sets, 1=obsessed
        double liquidityPreference,  // 0=cash-poor OK, 1=always keeps large reserve
        double tradeWillingness      // 0=never trades, 1=always trades — MUST have floor>0
) {

    /** Hard floor on tradeWillingness to prevent stalemate bots. */
    public static final double MIN_TRADE_WILLINGNESS = 0.10;

    public Personality {
        tradeWillingness = Math.max(MIN_TRADE_WILLINGNESS, tradeWillingness);
    }

    // -------------------------------------------------------------------------
    // Named archetypes
    // -------------------------------------------------------------------------

    public static Personality balanced() {
        return new Personality(0.5, 0.5, 0.5, 0.5, 0.5);
    }

    public static Personality aggressive() {
        return new Personality(0.85, 0.70, 0.70, 0.25, 0.55);
    }

    public static Personality cautious() {
        return new Personality(0.25, 0.20, 0.45, 0.80, 0.50);
    }

    public static Personality trader() {
        return new Personality(0.50, 0.55, 0.70, 0.40, 0.90);
    }

    public static Personality hoarder() {
        return new Personality(0.40, 0.35, 0.40, 0.85, 0.20);
    }

    // -------------------------------------------------------------------------
    // Sampling
    // -------------------------------------------------------------------------

    /**
     * Samples a personality from {@code archetype} by applying per-trait jitter.
     *
     * @param archetype base personality
     * @param jitter    max absolute deviation per trait (e.g. 0.10 for ±10%)
     * @param rng       deterministic source (per-bot derived stream)
     */
    public static Personality sample(Personality archetype, double jitter, RandomSource rng) {
        return new Personality(
                clamp(archetype.aggression()          + jitter(rng, jitter)),
                clamp(archetype.riskTolerance()       + jitter(rng, jitter)),
                clamp(archetype.monopolyAppetite()    + jitter(rng, jitter)),
                clamp(archetype.liquidityPreference() + jitter(rng, jitter)),
                clamp(archetype.tradeWillingness()    + jitter(rng, jitter))
        );
    }

    private static double jitter(RandomSource rng, double max) {
        return (rng.nextDouble() * 2 - 1) * max;
    }

    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
