package fi.monopoly.server.session;

import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.server.bot.CandidateAction;
import fi.monopoly.server.bot.Consideration;
import fi.monopoly.server.bot.DecisionContext;
import fi.monopoly.types.PlaceType;
import fi.monopoly.types.SpotType;
import fi.monopoly.types.StreetType;

import java.util.Arrays;
import java.util.List;

/**
 * Considerations used for the buy-vs-decline property purchase decision (Phase 3.3).
 *
 * <p>All considerations here are pure functions: no side effects, no I/O, deterministic
 * given the same {@link DecisionContext}. They can be unit-tested in isolation by
 * constructing a {@code DecisionContext} with a hand-built state.</p>
 *
 * <p>Veto considerations (AFFORDABILITY, RESERVE_MARGIN) score 0 to disqualify the BUY
 * action; positive considerations add signal when the veto passes.</p>
 */
final class BuyConsiderations {

    private BuyConsiderations() {}

    // -------------------------------------------------------------------------
    // Veto: AFFORDABILITY — hard block if the bot can't pay
    // -------------------------------------------------------------------------

    static final Consideration AFFORDABILITY = new Consideration() {
        @Override public String id() { return "affordability"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.BuyProperty buy)) return 1.0;
            PlayerSnapshot player = StrongBotStrategy.findPlayer(ctx.state(), ctx.botId());
            int cash = player != null ? player.cash() : 0;
            // input to curve = cash_after; veto curve returns 0 if < 0
            return ctx.params().curve("affordability").eval(cash - buy.price());
        }
    };

    // -------------------------------------------------------------------------
    // Soft veto: RESERVE_MARGIN — penalize buying too close to the reserve floor
    // -------------------------------------------------------------------------

    static final Consideration RESERVE_MARGIN = new Consideration() {
        @Override public String id() { return "reserve_margin"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.BuyProperty buy)) return 1.0;
            PlayerSnapshot player = StrongBotStrategy.findPlayer(ctx.state(), ctx.botId());
            int cash = player != null ? player.cash() : 0;
            int reserve = StrongBotStrategy.dynamicReserve(ctx.state(), ctx.botId(),
                    defaultConfig(ctx));
            int cashAfter = cash - buy.price();
            // Match pure-domain's set-completion leniency: allow buying up to 100 below reserve
            // when the purchase would complete a color monopoly.
            boolean completesSet = StrongBotStrategy.wouldCompleteSet(
                    ctx.state(), ctx.botId(), buy.propertyId());
            int effectiveReserve = completesSet ? Math.max(0, reserve - 100) : reserve;
            // normalised margin: 0 = exactly at threshold, positive = headroom, negative = below
            double normMargin = effectiveReserve > 0
                    ? (double)(cashAfter - effectiveReserve) / effectiveReserve : 0.0;
            return ctx.params().curve("reserve_margin").eval(normMargin);
        }
    };

    // -------------------------------------------------------------------------
    // SET_COMPLETION — big bonus for the last piece in a color group
    // -------------------------------------------------------------------------

    static final Consideration SET_COMPLETION = new Consideration() {
        @Override public String id() { return "set_completion"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.BuyProperty buy)) return 0.0;
            boolean completes = StrongBotStrategy.wouldCompleteSet(
                    ctx.state(), ctx.botId(), buy.propertyId());
            return ctx.params().curve("set_completion").eval(completes ? 1.0 : 0.0);
        }
    };

    // -------------------------------------------------------------------------
    // SET_PROGRESS — partial ownership in a group has growing value
    // -------------------------------------------------------------------------

    static final Consideration SET_PROGRESS = new Consideration() {
        @Override public String id() { return "set_progress"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.BuyProperty buy)) return 0.0;
            SpotType spot = StrongBotStrategy.spotType(buy.propertyId());
            StreetType group = spot.streetType;
            if (group == null || group.placeType != PlaceType.STREET) {
                // Utilities / railroads: flat moderate value
                return 0.45;
            }
            int groupSize = StrongBotStrategy.setSize(group);
            if (groupSize == 0) return 0.0;
            int owned = StrongBotStrategy.ownedInSet(ctx.state(), ctx.botId(), group);
            // Include the property being considered
            double progress = (double)(owned + 1) / groupSize;
            return ctx.params().curve("set_progress").eval(progress);
        }
    };

    // -------------------------------------------------------------------------
    // PROPERTY_ROI — landing-probability-weighted group rank
    // -------------------------------------------------------------------------

    // Group ranking by expected $/opponent-roll at 3 houses (from published Markov tables).
    // Normalised so orange=1.0, brown=0.0.
    private static final double[] ROI_BY_GROUP_RANK = {
        1.00,  // Orange     (rank 1 — highest ROI, most-landed coming out of jail)
        0.89,  // Red
        0.78,  // Yellow
        0.67,  // DarkBlue   (hotel value dominates late game)
        0.56,  // Green
        0.44,  // Pink (LightPurple)
        0.33,  // LightBlue
        0.11   // Brown      (lowest ROI)
    };

    private static final List<StreetType> GROUP_ROI_ORDER = Arrays.stream(new StreetType[]{})
            .toList(); // placeholder — resolved at runtime via spotType

    static final Consideration PROPERTY_ROI = new Consideration() {
        @Override public String id() { return "property_roi"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.BuyProperty buy)) return 0.0;
            double roiRank = groupRoiRank(buy.propertyId());
            return ctx.params().curve("property_roi").eval(roiRank);
        }
    };

    /** Returns a normalised ROI rank in [0,1] for the group containing {@code propId}. */
    static double groupRoiRank(String propId) {
        SpotType spot;
        try { spot = SpotType.valueOf(propId); } catch (Exception e) { return 0.5; }
        StreetType group = spot.streetType;
        if (group == null) return 0.50;

        // Compressed ROI ranks [0.70, 1.00] — preserves the orange > red > … > brown ordering
        // but prevents low-ROI groups from dragging the multiplicative product below the buy
        // threshold.  Even brown monopoly is worth owning; the ranking only modulates bid
        // ceiling and build priority, not whether to buy at all at list price.
        return switch (group.name()) {
            case "ORANGE"     -> 1.00;
            case "RED"        -> 0.96;
            case "YELLOW"     -> 0.92;
            case "DARK_BLUE"  -> 0.88;
            case "GREEN"      -> 0.84;
            case "PINK",
                 "LIGHT_PURPLE" -> 0.80;
            case "LIGHT_BLUE" -> 0.75;
            case "BROWN"      -> 0.70;
            default           -> 0.80; // utilities / railroads: moderate
        };
    }

    // -------------------------------------------------------------------------
    // BUY_GAME_PHASE — gates late/mid-game standalone purchases
    //
    // Mirrors PureDomainStrategy's buyThreshold() logic:
    //   Late game (≤10 unowned): veto any property where the bot has no existing
    //     presence in the group (threshold 5.5 in PD effectively blocks these).
    //   Mid game (≤20 unowned): veto standalone railroads/utilities
    //     (PD thresholds 3.0/5.0 are too high for their typical buyScore).
    // Exemptions: set-completing purchases and opponent-blocking always pass.
    // -------------------------------------------------------------------------

    static final Consideration BUY_GAME_PHASE = new Consideration() {
        @Override public String id() { return "buy_game_phase"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.BuyProperty buy)) return 1.0;
            SpotType spot = StrongBotStrategy.spotType(buy.propertyId());
            StreetType group = spot.streetType;
            if (group == null) return ctx.params().curve("buy_game_phase").eval(1.0);

            // Always allow set-completing purchases
            if (StrongBotStrategy.wouldCompleteSet(ctx.state(), ctx.botId(), buy.propertyId())) {
                return ctx.params().curve("buy_game_phase").eval(1.0);
            }
            // Allow purchases that block an opponent one step from a monopoly
            int setSize = StrongBotStrategy.setSize(group);
            if (setSize > 1) {
                boolean blocksOpponent = ctx.state().players().stream()
                        .filter(p -> !p.playerId().equals(ctx.botId()) && !p.bankrupt() && !p.eliminated())
                        .anyMatch(p -> StrongBotStrategy.ownedInSet(ctx.state(), p.playerId(), group) == setSize - 1);
                if (blocksOpponent) return ctx.params().curve("buy_game_phase").eval(1.0);
            }

            boolean hasPresenceInGroup = StrongBotStrategy.ownedInSet(ctx.state(), ctx.botId(), group) > 0;
            int unowned = StrongBotStrategy.unownedCount(ctx.state());

            if (unowned <= 10) {
                // Late game: veto standalone (matches PD's effective threshold=5.5)
                return ctx.params().curve("buy_game_phase").eval(hasPresenceInGroup ? 1.0 : 0.0);
            }
            if (unowned <= 20 && group.placeType != PlaceType.STREET && !hasPresenceInGroup) {
                // Mid game: veto standalone railroad/utility (PD threshold 3.0–5.0)
                return ctx.params().curve("buy_game_phase").eval(0.0);
            }
            return ctx.params().curve("buy_game_phase").eval(1.0);
        }
    };

    // -------------------------------------------------------------------------
    // Ordered list (declared AFTER all static fields to avoid forward references)
    // -------------------------------------------------------------------------

    static final List<Consideration> BUY_CONSIDERATIONS = List.of(
            AFFORDABILITY,
            RESERVE_MARGIN,
            SET_COMPLETION,
            SET_PROGRESS,
            PROPERTY_ROI,
            BUY_GAME_PHASE
    );

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static StrongBotConfig defaultConfig(DecisionContext ctx) {
        // UtilityStrategy doesn't hold a StrongBotConfig; use defaults() as the
        // reserve-calculation backing config (the exact value is a rough estimate anyway).
        return StrongBotConfig.defaults();
    }
}
