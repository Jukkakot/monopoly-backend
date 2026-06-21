package fi.monopoly.server.session;

import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.domain.session.PropertyStateSnapshot;
import fi.monopoly.server.bot.CandidateAction;
import fi.monopoly.server.bot.Consideration;
import fi.monopoly.server.bot.DecisionContext;
import fi.monopoly.types.PlaceType;
import fi.monopoly.types.StreetType;

import java.util.List;

/**
 * Considerations for the unmortgage decision during {@code WAITING_FOR_END_TURN} (Phase 4.2).
 *
 * <p>Unmortgaging only makes sense when the bot owns the full color group — otherwise
 * the rent income is zero and the unmortgage cost is wasted. The IAUS model captures this
 * as a veto consideration.</p>
 */
final class UnmortgageConsiderations {

    private UnmortgageConsiderations() {}

    // -------------------------------------------------------------------------
    // Veto: AFFORDABILITY — can't unmortgage if cash_after < reserve
    // -------------------------------------------------------------------------

    static final Consideration UNMORTGAGE_AFFORDABILITY = new Consideration() {
        @Override public String id() { return "unmortgage_affordability"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.Unmortgage u)) return 1.0;
            PlayerSnapshot player = StrongBotStrategy.findPlayer(ctx.state(), ctx.botId());
            int cash = player != null ? player.cash() : 0;
            int cost = StrongBotStrategy.unmortgageCost(u.propertyId());
            return ctx.params().curve("unmortgage_affordability").eval(cash - cost);
        }
    };

    // -------------------------------------------------------------------------
    // Veto: GROUP COMPLETENESS — only unmortgage when you own the entire group
    // -------------------------------------------------------------------------

    static final Consideration UNMORTGAGE_GROUP_COMPLETE = new Consideration() {
        @Override public String id() { return "unmortgage_group_complete"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.Unmortgage u)) return 1.0;
            StreetType group = StrongBotStrategy.spotType(u.propertyId()).streetType;
            boolean fullGroup = StrongBotStrategy.botOwnsFullGroup(ctx.state(), ctx.botId(), group);
            return ctx.params().curve("unmortgage_group_complete").eval(fullGroup ? 1.0 : 0.0);
        }
    };

    // -------------------------------------------------------------------------
    // GROUP ROI — same ranking as buy/build decisions
    // -------------------------------------------------------------------------

    static final Consideration UNMORTGAGE_GROUP_ROI = new Consideration() {
        @Override public String id() { return "unmortgage_group_roi"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.Unmortgage u)) return 0.0;
            double roiRank = BuyConsiderations.groupRoiRank(u.propertyId());
            return ctx.params().curve("unmortgage_group_roi").eval(roiRank);
        }
    };

    // -------------------------------------------------------------------------
    // CASH COMFORT — score improves when there's plenty of cash above reserve
    // (avoid draining down to reserve just to unmortgage)
    // -------------------------------------------------------------------------

    static final Consideration UNMORTGAGE_CASH_COMFORT = new Consideration() {
        @Override public String id() { return "unmortgage_cash_comfort"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.Unmortgage u)) return 1.0;
            PlayerSnapshot player = StrongBotStrategy.findPlayer(ctx.state(), ctx.botId());
            int cash = player != null ? player.cash() : 0;
            int cost = StrongBotStrategy.unmortgageCost(u.propertyId());
            int reserve = StrongBotStrategy.dynamicReserve(ctx.state(), ctx.botId(),
                    StrongBotConfig.defaults());
            double posFactor = StrongBotStrategy.positionFactor(ctx.state(), ctx.botId());
            int posAdjReserve = Math.max(0, (int)(reserve / posFactor));
            // Comfort = how much headroom remains above position-adjusted reserve
            double comfort = posAdjReserve > 0 ? (double)(cash - cost - posAdjReserve) / posAdjReserve : 0.0;
            return ctx.params().curve("unmortgage_cash_comfort").eval(comfort);
        }
    };

    static final List<Consideration> UNMORTGAGE_CONSIDERATIONS = List.of(
            UNMORTGAGE_AFFORDABILITY,
            UNMORTGAGE_GROUP_COMPLETE,
            UNMORTGAGE_GROUP_ROI,
            UNMORTGAGE_CASH_COMFORT
    );
}
