package fi.monopoly.server.session;

import fi.monopoly.domain.session.AuctionState;
import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.server.bot.CandidateAction;
import fi.monopoly.server.bot.Consideration;
import fi.monopoly.server.bot.DecisionContext;
import fi.monopoly.types.PlaceType;
import fi.monopoly.types.SpotType;
import fi.monopoly.types.StreetType;

import java.util.List;

/**
 * Considerations for the auction bidding decision (Phase 4.4).
 *
 * <p>The combined utility score represents willingness to pay up to face price.
 * The caller multiplies the score by a personality-driven aggression factor to
 * compute the bid ceiling; affordability veto prevents bidding beyond cash minus reserve.</p>
 */
final class AuctionConsiderations {

    private AuctionConsiderations() {}

    // -------------------------------------------------------------------------
    // Veto: AFFORDABILITY — can't bid if cash - reserve < minimumNextBid
    // -------------------------------------------------------------------------

    static final Consideration BID_AFFORDABILITY = new Consideration() {
        @Override public String id() { return "bid_affordability"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.AuctionBid bid)) return 1.0;
            PlayerSnapshot player = StrongBotStrategy.findPlayer(ctx.state(), ctx.botId());
            int cash = player != null ? player.cash() : 0;
            StrongBotConfig cfg = StrongBotConfig.defaults();
            int reserve = Math.min(StrongBotStrategy.dynamicReserve(ctx.state(), ctx.botId(), cfg),
                    cfg.dangerCashReserve());
            return ctx.params().curve("bid_affordability").eval(cash - reserve - bid.amount());
        }
    };

    // -------------------------------------------------------------------------
    // BID VALUE RATIO — score rises when bid is well below face price
    // -------------------------------------------------------------------------

    static final Consideration BID_VALUE_RATIO = new Consideration() {
        @Override public String id() { return "bid_value_ratio"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.AuctionBid bid)) return 0.5;
            int facePrice = facePrice(bid.propertyId());
            if (facePrice <= 0) return 0.5;
            // input: fraction of face price REMAINING as headroom (1 = bidding at 0, 0 = bidding at face price)
            double ratio = 1.0 - (double) bid.amount() / facePrice;
            return ctx.params().curve("bid_value_ratio").eval(ratio);
        }
    };

    // -------------------------------------------------------------------------
    // SET COMPLETION — bonus when this property completes our monopoly.
    // When it doesn't complete, returns the curve's "below threshold" neutral value
    // (configured in BotParams, personality-tuned) rather than a hard 0.
    // -------------------------------------------------------------------------

    static final Consideration BID_SET_COMPLETION = new Consideration() {
        @Override public String id() { return "bid_set_completion"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.AuctionBid bid)) return 0.5;
            boolean completes = StrongBotStrategy.wouldCompleteSet(ctx.state(), ctx.botId(), bid.propertyId());
            // curve is step(0.5, neutral, 1.0) — neutral when not completing, 1.0 when completing
            return ctx.params().curve("bid_set_completion").eval(completes ? 1.0 : 0.0);
        }
    };

    // -------------------------------------------------------------------------
    // OPPONENT BLOCKING — bonus when winning this property prevents an opponent
    // from completing their set. Neutral when blocking isn't relevant.
    // -------------------------------------------------------------------------

    static final Consideration BID_OPPONENT_BLOCKING = new Consideration() {
        @Override public String id() { return "bid_opponent_blocking"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.AuctionBid bid)) return 0.5;
            StreetType group = StrongBotStrategy.spotType(bid.propertyId()).streetType;
            if (group == null || group.placeType != PlaceType.STREET) {
                // Non-street: can't form monopoly → use the below-threshold neutral value
                return ctx.params().curve("bid_opponent_blocking").eval(0.0);
            }
            int groupSize = StrongBotStrategy.setSize(group);
            if (groupSize <= 1) return ctx.params().curve("bid_opponent_blocking").eval(0.0);
            boolean blocksOpponent = ctx.state().players().stream()
                    .filter(p -> !p.playerId().equals(ctx.botId()) && !p.bankrupt() && !p.eliminated())
                    .anyMatch(p -> StrongBotStrategy.ownedInSet(ctx.state(), p.playerId(), group) == groupSize - 1);
            return ctx.params().curve("bid_opponent_blocking").eval(blocksOpponent ? 1.0 : 0.0);
        }
    };

    // -------------------------------------------------------------------------
    // GROUP ROI — same property-group ranking as buy/build decisions
    // -------------------------------------------------------------------------

    static final Consideration BID_GROUP_ROI = new Consideration() {
        @Override public String id() { return "bid_group_roi"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.AuctionBid bid)) return 0.0;
            return ctx.params().curve("bid_group_roi").eval(BuyConsiderations.groupRoiRank(bid.propertyId()));
        }
    };

    static final List<Consideration> BID_CONSIDERATIONS = List.of(
            BID_AFFORDABILITY,
            BID_VALUE_RATIO,
            BID_SET_COMPLETION,
            BID_OPPONENT_BLOCKING,
            BID_GROUP_ROI
    );

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    static int facePrice(String propertyId) {
        try {
            return SpotType.valueOf(propertyId).getIntegerProperty("price");
        } catch (Exception e) {
            return 0;
        }
    }
}
