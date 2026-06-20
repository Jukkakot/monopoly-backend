package fi.monopoly.server.bot;

import java.util.HashMap;
import java.util.Map;

/**
 * Externalized, versioned configuration for one bot instance.
 *
 * <p>Contains per-consideration weights, response curves, a personality vector,
 * and scalar thresholds. Loaded once per game per bot; content is immutable after
 * construction so it is safe to share across parallel considerations.</p>
 *
 * <p>Phase 3: weights and curves cover the buy/auction decision. Later phases extend
 * the maps as more considerations are added. Phase 5 will add JSON loading and
 * content-hashing for harness reproducibility.</p>
 */
public record BotParams(
        String id,
        Map<String, Double> weights,   // consideration-id → weight multiplier
        Map<String, Curve>  curves,    // consideration-id → response curve
        Personality personality
) {

    // -------------------------------------------------------------------------
    // Default parameter sets
    // -------------------------------------------------------------------------

    /**
     * Defaults tuned around the "3-house rule" valuation numbers and a balanced
     * personality. This is the baseline for {@code utility-v1}.
     */
    public static BotParams defaults() {
        return forPersonality("defaults", Personality.balanced());
    }

    public static BotParams aggressive() {
        return forPersonality("aggressive", Personality.aggressive());
    }

    public static BotParams cautious() {
        return forPersonality("cautious", Personality.cautious());
    }

    public static BotParams forPersonality(String id, Personality p) {
        Map<String, Double> w = new HashMap<>();
        Map<String, Curve>  c = new HashMap<>();

        // ---- Buy-vs-decline decision ----------------------------------------
        // Veto: can't afford → score 0
        w.put("affordability", 1.0);
        c.put("affordability", Curve.veto(0.0));  // input = cash_after (may be negative)

        // Reserve margin: scored by how much cash remains above the reserve floor.
        // aggressive bots accept thinner margins; cautious bots need more headroom.
        double reserveSteepness = 2.0 + (p.riskTolerance() - 0.5) * 2.0;
        w.put("reserve_margin", 1.0);
        c.put("reserve_margin", Curve.logistic(0.0, reserveSteepness));  // input = (cash_after - reserve) / reserve

        // Set-completion bonus: high value for the last piece in a color group.
        double completionWeight = 0.5 + p.monopolyAppetite() * 0.8;
        w.put("set_completion", completionWeight);
        c.put("set_completion", Curve.step(0.5, 0.0, 1.0));  // input: 1 if completes, 0 otherwise

        // Set progress: partial ownership in a group has growing value.
        double progressWeight = 0.3 + p.monopolyAppetite() * 0.5;
        w.put("set_progress", progressWeight);
        c.put("set_progress", Curve.polynomial(1.5, 1.0, 0.0));  // input = ownedInGroup / groupSize

        // Property ROI rank: higher for orange/red groups, lower for brown/utilities.
        double roiWeight = 0.2 + p.aggression() * 0.4;
        w.put("property_roi", roiWeight);
        c.put("property_roi", Curve.linear(1.0, 0.0));  // input = normalised ROI rank [0,1]

        // Auction-opportunity baseline: score the "decline and go to auction" option.
        // Aggressive bots prefer direct purchase; cautious ones like the auction floor.
        double auctionBase = 0.15 + (1.0 - p.aggression()) * 0.25;
        w.put("auction_baseline", auctionBase);

        // Buy threshold: minimum combined utility to trigger BUY over DECLINE.
        // Lower = more purchase-happy; higher = more selective.
        double buyThreshold = 0.20 + p.liquidityPreference() * 0.15;
        w.put("buy_threshold", buyThreshold);

        // ---- Build-houses decision ------------------------------------------
        // Veto: can't afford → 0
        w.put("build_affordability", 1.0);
        c.put("build_affordability", Curve.veto(0.0));  // input = cash_after_build

        // Reserve margin after building
        double buildReserveSteepness = 1.5 + (p.riskTolerance() - 0.5) * 2.0;
        w.put("build_reserve_margin", 1.0);
        c.put("build_reserve_margin", Curve.logistic(0.0, buildReserveSteepness));

        // ROI rank of the color group
        double buildRoiWeight = 0.3 + p.monopolyAppetite() * 0.4;
        w.put("build_group_roi", buildRoiWeight);
        c.put("build_group_roi", Curve.linear(1.0, 0.0));

        // Level efficiency: input is already normalised [0,1] by BuildConsiderations
        w.put("build_level_efficiency", 1.0);
        c.put("build_level_efficiency", Curve.identity());

        // Baseline for comparing build vs. end-turn (lower → builds more aggressively)
        double buildBaseline = 0.15 + (1.0 - p.aggression()) * 0.15;
        w.put("build_end_turn_baseline", buildBaseline);

        // ---- Unmortgage decision --------------------------------------------
        // Veto: can't afford (input = cash_after_unmortgage)
        w.put("unmortgage_affordability", 1.0);
        c.put("unmortgage_affordability", Curve.veto(0.0));

        // Veto: must own the full group (input = 1 if full, 0 if not)
        w.put("unmortgage_group_complete", 1.0);
        c.put("unmortgage_group_complete", Curve.step(0.5, 0.0, 1.0));

        // ROI rank of the group
        double unmortgageRoiWeight = 0.3 + p.monopolyAppetite() * 0.3;
        w.put("unmortgage_group_roi", unmortgageRoiWeight);
        c.put("unmortgage_group_roi", Curve.linear(1.0, 0.0));

        // Cash comfort (how much headroom remains; conservative bots demand more)
        double unmortgageComfortSteepness = 1.5 + p.liquidityPreference() * 2.0;
        w.put("unmortgage_cash_comfort", 1.0);
        c.put("unmortgage_cash_comfort", Curve.logistic(0.0, unmortgageComfortSteepness));

        // Unmortgage baseline (vs. saving cash / ending turn)
        double unmortgageBaseline = 0.25 + (1.0 - p.aggression()) * 0.15;
        w.put("unmortgage_end_turn_baseline", unmortgageBaseline);

        return new BotParams(id, Map.copyOf(w), Map.copyOf(c), p);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public double weight(String key) {
        return weights.getOrDefault(key, 1.0);
    }

    public double weight(String key, double fallback) {
        return weights.getOrDefault(key, fallback);
    }

    public Curve curve(String key) {
        Curve c = curves.get(key);
        if (c == null) throw new IllegalArgumentException("No curve registered for '" + key + "'");
        return c;
    }
}
