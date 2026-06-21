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

        // Reserve margin: hard veto when cash_after < reserve (matches pure-domain's strict hard decline).
        // A logistic was too lenient and caused over-buying followed by bankruptcy.
        w.put("reserve_margin", 1.0);
        c.put("reserve_margin", Curve.veto(0.0));  // input = (cash_after - reserve) / reserve; 0 at threshold passes

        // Set-completion bonus: returns neutral (0.7) for non-completing purchases,
        // 1.0 when the purchase completes a color monopoly.  Weight stays at 1.0 so
        // it acts as a pure bonus — NOT a veto — in the multiplicative combiner.
        w.put("set_completion", 1.0);
        c.put("set_completion", Curve.step(0.5, 0.7, 1.0));  // input: 1 if completes, 0 otherwise

        // Set progress: square-root curve so even the first property in a group scores ~0.58+.
        // Convex (1.5 exponent) was too punishing for early purchases of large groups.
        w.put("set_progress", 1.0);
        c.put("set_progress", Curve.polynomial(0.5, 1.0, 0.0));  // input = (owned+1) / groupSize

        // Property ROI rank: higher for orange/red groups, lower for brown/utilities.
        w.put("property_roi", 1.0);
        c.put("property_roi", Curve.linear(1.0, 0.0));  // input = normalised ROI rank [0,1]

        // Auction-opportunity baseline: how attractive "decline and go to auction" is.
        // Must stay well below the typical buy score (~0.3–0.6 for good properties).
        double auctionBase = 0.02 + (1.0 - p.aggression()) * 0.05;
        w.put("auction_baseline", auctionBase);

        // Buy threshold: minimum combined utility to trigger BUY over DECLINE.
        // Lower = more purchase-happy; higher = more selective.
        double buyThreshold = 0.20 + p.liquidityPreference() * 0.15;
        w.put("buy_threshold", buyThreshold);

        // Game-phase gate: step(0.5, 0, 1) — input=0 vetoes, input=1 is neutral pass-through.
        // Mirrors PD's phase-aware buyThreshold — blocks late/mid-game standalone purchases.
        w.put("buy_game_phase", 1.0);
        c.put("buy_game_phase", Curve.step(0.5, 0.0, 1.0));

        // ---- Build-houses decision ------------------------------------------
        // Veto: can't afford → 0
        w.put("build_affordability", 1.0);
        c.put("build_affordability", Curve.veto(0.0));  // input = cash_after_build

        // Reserve margin after building
        double buildReserveSteepness = 1.5 + (p.riskTolerance() - 0.5) * 2.0;
        w.put("build_reserve_margin", 1.0);
        c.put("build_reserve_margin", Curve.logistic(0.0, buildReserveSteepness));

        // ROI rank of the color group — weight 1.0 so it scales the score linearly
        w.put("build_group_roi", 1.0);
        c.put("build_group_roi", Curve.linear(1.0, 0.0));

        // Level efficiency: input is already normalised [0,1] by BuildConsiderations
        w.put("build_level_efficiency", 1.0);
        c.put("build_level_efficiency", Curve.identity());

        // Baseline for comparing build vs. end-turn
        double buildBaseline = 0.05 + (1.0 - p.aggression()) * 0.08;
        w.put("build_end_turn_baseline", buildBaseline);

        // ---- Unmortgage decision --------------------------------------------
        // Veto: can't afford (input = cash_after_unmortgage)
        w.put("unmortgage_affordability", 1.0);
        c.put("unmortgage_affordability", Curve.veto(0.0));

        // Veto: must own the full group (input = 1 if full, 0 if not)
        w.put("unmortgage_group_complete", 1.0);
        c.put("unmortgage_group_complete", Curve.step(0.5, 0.0, 1.0));

        // ROI rank of the group — weight 1.0
        w.put("unmortgage_group_roi", 1.0);
        c.put("unmortgage_group_roi", Curve.linear(1.0, 0.0));

        // Cash comfort (how much headroom remains; conservative bots demand more)
        double unmortgageComfortSteepness = 1.5 + p.liquidityPreference() * 2.0;
        w.put("unmortgage_cash_comfort", 1.0);
        c.put("unmortgage_cash_comfort", Curve.logistic(0.0, unmortgageComfortSteepness));

        // Unmortgage baseline (vs. saving cash / ending turn)
        double unmortgageBaseline = 0.05 + (1.0 - p.aggression()) * 0.08;
        w.put("unmortgage_end_turn_baseline", unmortgageBaseline);

        // ---- Auction bid decision -------------------------------------------
        // Veto: can't afford minimum bid (input = cash - reserve - minBid)
        w.put("bid_affordability", 1.0);
        c.put("bid_affordability", Curve.veto(0.0));

        // Value ratio: 0.5 (neutral) at face price, 1.0 at free.  Must NOT return 0 at face price
        // because that would zero the IAUS product and always pass at face price.
        // Pure-domain bids up to 1.30× face price; this keeps IAUS positive up to the ceiling.
        w.put("bid_value_ratio", 1.0);
        c.put("bid_value_ratio", Curve.linear(0.5, 0.5));  // input = 1 - bid/facePrice → [0.5, 1.0]

        // Set completion: neutral (0.5) when not completing, bonus (1.0) when completing.
        // Personality shifts the curve via the "neutral" value rather than the weight.
        double bidCompletionNeutral = 0.35 + p.monopolyAppetite() * 0.25;  // 0.475 balanced, 0.55 aggressive
        w.put("bid_set_completion", 1.0);
        c.put("bid_set_completion", Curve.step(0.5, bidCompletionNeutral, 1.0));

        // Opponent blocking: neutral when not blocking, bonus when we'd prevent opponent monopoly
        double bidBlockingNeutral = 0.35 + p.aggression() * 0.20;  // 0.45 balanced, 0.52 aggressive
        w.put("bid_opponent_blocking", 1.0);
        c.put("bid_opponent_blocking", Curve.step(0.5, bidBlockingNeutral, 1.0));

        // Group ROI — linear from 0 to 1 based on published Markov ROI rank
        w.put("bid_group_roi", 1.0);
        c.put("bid_group_roi", Curve.linear(1.0, 0.0));

        // Bid aggression multiplier: face_price × bidAggression = bid ceiling.
        // Raised to be competitive with pure-domain's 1.30 ceiling; aggressive/hoarder bots bid higher.
        double bidAggression = 1.10 + p.aggression() * 0.30 + p.monopolyAppetite() * 0.20;
        w.put("bid_aggression", bidAggression);

        // Bid pass baseline: minimum combined score required to place any bid
        double bidPassBaseline = 0.08 + (1.0 - p.aggression()) * 0.10;
        w.put("bid_pass_baseline", bidPassBaseline);

        // ---- Trade response decision -----------------------------------------
        // Veto: can't afford the cash portion of the deal (input = cash - cashGiven)
        w.put("trade_cash_affordability", 1.0);
        c.put("trade_cash_affordability", Curve.veto(0.0));

        // Fairness ratio: logistic on received/given value ratio [0,1].
        // Aggressive bots accept lower ratios (lower midpoint); cautious demand more.
        double tradeFairMidpoint = 0.65 - p.aggression() * 0.15;  // aggressive≈0.525, balanced≈0.575, cautious≈0.65
        w.put("trade_fairness_ratio", 1.0);
        c.put("trade_fairness_ratio", Curve.logistic(tradeFairMidpoint, 6.0));

        // Monopoly completion: neutral when not completing, bonus when we get our missing piece.
        double tradeCompletionNeutral = 0.40 + p.monopolyAppetite() * 0.15;  // balanced≈0.475
        w.put("trade_monopoly_completion", 1.0);
        c.put("trade_monopoly_completion", Curve.step(0.5, tradeCompletionNeutral, 1.0));

        // Gift danger: penalty when giving would complete the opponent's set.
        // Input: 1.0 = safe, 0.0 = dangerous → curve returns penalty when dangerous.
        // Aggressive bots tolerate the risk more (higher penalty value = less deterrent).
        double tradeGiftDangerPenalty = 0.05 + p.aggression() * 0.20;  // aggressive≈0.25, balanced≈0.15, cautious≈0.05
        w.put("trade_gift_danger", 1.0);
        c.put("trade_gift_danger", Curve.step(0.5, tradeGiftDangerPenalty, 1.0));

        // Accept threshold: minimum combined score to ACCEPT (not counter or decline).
        // Liquidity-preferring bots demand higher utility before giving up cash/property.
        double tradeAcceptBaseline = 0.35 + p.liquidityPreference() * 0.10;  // balanced≈0.40
        w.put("trade_accept_baseline", tradeAcceptBaseline);

        // Counter threshold: minimum combined score to COUNTER (rather than flat decline).
        // Trades scoring below this are so unfair the bot won't even negotiate — explicitly
        // declined to prevent PureDomainStrategy (which scales fairness tolerance with
        // positionFactor) from accepting them on the bot's behalf.
        // ~0.057 = "€50 for €350 property" with balanced personality, safely below 0.09.
        double tradeCounterBaseline = 0.06 + p.liquidityPreference() * 0.03;  // balanced≈0.075
        w.put("trade_counter_baseline", tradeCounterBaseline);

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
