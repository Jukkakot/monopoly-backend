package fi.monopoly.server.session;

import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.server.bot.CandidateAction;
import fi.monopoly.server.bot.Consideration;
import fi.monopoly.server.bot.DecisionContext;
import fi.monopoly.types.SpotType;
import fi.monopoly.types.StreetType;

import java.util.List;

/**
 * Considerations for the build-houses-vs-end-turn decision (Phase 4.1).
 *
 * <p>All considerations are pure functions evaluated with the IAUS multiplicative combiner.
 * The bot compares the best {@link CandidateAction.BuildHouses} score against
 * {@code build_end_turn_baseline} to decide whether to build or pass.</p>
 *
 * <h3>Key reference numbers (Markov/3-house rule)</h3>
 * <ul>
 *   <li>Marginal rent jump is largest at the 3rd house: e.g. Illinois Ave
 *       1→2 +200%, 2→3 +150%, 3→4 only +23%.</li>
 *   <li>Building to 3 also triggers housing shortage (only 32 houses exist),
 *       denying opponents development.</li>
 *   <li>Hotel improvement over 4th house is small (~10%). Building a hotel mainly
 *       matters when opponents have houses and you want the supply.</li>
 * </ul>
 */
final class BuildConsiderations {

    private BuildConsiderations() {}

    // -------------------------------------------------------------------------
    // Veto: AFFORDABILITY — hard block if bot cannot afford this build round
    // -------------------------------------------------------------------------

    static final Consideration BUILD_AFFORDABILITY = new Consideration() {
        @Override public String id() { return "build_affordability"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.BuildHouses build)) return 1.0;
            PlayerSnapshot player = StrongBotStrategy.findPlayer(ctx.state(), ctx.botId());
            int cash = player != null ? player.cash() : 0;
            // curve: veto if cash_after = cash - buildCost < 0
            return ctx.params().curve("build_affordability").eval(cash - build.buildCost());
        }
    };

    // -------------------------------------------------------------------------
    // Soft veto: RESERVE MARGIN — penalise building too close to the reserve floor
    // -------------------------------------------------------------------------

    static final Consideration BUILD_RESERVE_MARGIN = new Consideration() {
        @Override public String id() { return "build_reserve_margin"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.BuildHouses build)) return 1.0;
            PlayerSnapshot player = StrongBotStrategy.findPlayer(ctx.state(), ctx.botId());
            int cash = player != null ? player.cash() : 0;
            int reserve = StrongBotStrategy.dynamicReserve(ctx.state(), ctx.botId(),
                    StrongBotConfig.defaults());
            double posFactor = StrongBotStrategy.positionFactor(ctx.state(), ctx.botId());
            int posAdjReserve = Math.max(0, (int)(reserve / posFactor));
            double normMargin = posAdjReserve > 0
                    ? (double)(cash - build.buildCost() - posAdjReserve) / posAdjReserve : 0.0;
            return ctx.params().curve("build_reserve_margin").eval(normMargin);
        }
    };

    // -------------------------------------------------------------------------
    // GROUP ROI — group ranking by expected $/opponent-roll at 3 houses
    // -------------------------------------------------------------------------

    static final Consideration BUILD_GROUP_ROI = new Consideration() {
        @Override public String id() { return "build_group_roi"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.BuildHouses build)) return 0.0;
            double roiRank = BuyConsiderations.groupRoiRank(build.propertyId());
            return ctx.params().curve("build_group_roi").eval(roiRank);
        }
    };

    // -------------------------------------------------------------------------
    // LEVEL EFFICIENCY — captures the "3-house rule" (peak marginal value at level 2→3)
    // -------------------------------------------------------------------------

    // Normalised marginal rent gain by build level (currentMaxLevel = level before building):
    //   0 → building 1st house: moderate gain (~0.45)
    //   1 → building 2nd house: large gain (~0.70)
    //   2 → building 3rd house: peak gain (~1.00) ← 3-house rule sweet spot
    //   3 → building 4th house: diminishing (~0.40)
    //   4 → building hotel:     small gain (~0.25)
    private static final double[] LEVEL_EFFICIENCY = {0.45, 0.70, 1.00, 0.40, 0.25};

    static final Consideration BUILD_LEVEL_EFFICIENCY = new Consideration() {
        @Override public String id() { return "build_level_efficiency"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.BuildHouses build)) return 0.0;
            int level = Math.max(0, Math.min(4, build.currentMaxLevel()));
            return ctx.params().curve("build_level_efficiency").eval(LEVEL_EFFICIENCY[level]);
        }
    };

    // -------------------------------------------------------------------------
    // Ordered list (declared after all statics to avoid forward references)
    // -------------------------------------------------------------------------

    static final List<Consideration> BUILD_CONSIDERATIONS = List.of(
            BUILD_AFFORDABILITY,
            BUILD_RESERVE_MARGIN,
            BUILD_GROUP_ROI,
            BUILD_LEVEL_EFFICIENCY
    );
}
