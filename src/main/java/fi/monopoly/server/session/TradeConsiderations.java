package fi.monopoly.server.session;

import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.session.TradeSelectionState;
import fi.monopoly.server.bot.CandidateAction;
import fi.monopoly.server.bot.Consideration;
import fi.monopoly.server.bot.DecisionContext;
import fi.monopoly.types.SpotType;

import java.util.List;

/**
 * Considerations for the trade-response decision (Phase 4.5).
 *
 * <p>These score the utility of <em>accepting</em> an incoming trade offer.
 * The combined score is compared against {@code trade_accept_baseline} (ACCEPT)
 * and {@code trade_counter_baseline} (COUNTER); below both → DECLINE.</p>
 *
 * <p>Counter-editing and trade-editing are still delegated to {@link PureDomainStrategy}
 * — only the accept/counter/decline branch is migrated here.</p>
 */
final class TradeConsiderations {

    private TradeConsiderations() {}

    // -------------------------------------------------------------------------
    // Veto: CASH AFFORDABILITY — can't pay the cash portion of the deal
    // -------------------------------------------------------------------------

    static final Consideration TRADE_CASH_AFFORDABILITY = new Consideration() {
        @Override public String id() { return "trade_cash_affordability"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.AcceptTrade t)) return 1.0;
            PlayerSnapshot player = StrongBotStrategy.findPlayer(ctx.state(), ctx.botId());
            int cash = player != null ? player.cash() : 0;
            return ctx.params().curve("trade_cash_affordability").eval(cash - t.giving().moneyAmount());
        }
    };

    // -------------------------------------------------------------------------
    // FAIRNESS RATIO — how much value do we receive relative to what we give?
    // Input: min(1.0, totalReceived / totalGiven); 1.0 = fair or better
    // -------------------------------------------------------------------------

    static final Consideration TRADE_FAIRNESS_RATIO = new Consideration() {
        @Override public String id() { return "trade_fairness_ratio"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.AcceptTrade t)) return 0.5;
            int received = totalValue(ctx.state(), t.receiving());
            int given    = totalValue(ctx.state(), t.giving());
            if (given <= 0) return received > 0 ? 1.0 : 0.5;
            double ratio = Math.min(1.0, (double) received / given);
            return ctx.params().curve("trade_fairness_ratio").eval(ratio);
        }
    };

    // -------------------------------------------------------------------------
    // MONOPOLY COMPLETION — bonus when a received property completes our set
    // -------------------------------------------------------------------------

    static final Consideration TRADE_MONOPOLY_COMPLETION = new Consideration() {
        @Override public String id() { return "trade_monopoly_completion"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.AcceptTrade t)) return 0.5;
            boolean completes = t.receiving().propertyIds().stream()
                    .anyMatch(id -> StrongBotStrategy.wouldCompleteSet(ctx.state(), ctx.botId(), id));
            return ctx.params().curve("trade_monopoly_completion").eval(completes ? 1.0 : 0.0);
        }
    };

    // -------------------------------------------------------------------------
    // GIFT DANGER — penalty when giving a property completes the partner's set
    // Input: 1.0 = safe (no danger), 0.0 = dangerous (gifts opponent a monopoly)
    // -------------------------------------------------------------------------

    static final Consideration TRADE_GIFT_DANGER = new Consideration() {
        @Override public String id() { return "trade_gift_danger"; }

        @Override public double score(DecisionContext ctx) {
            if (!(ctx.action() instanceof CandidateAction.AcceptTrade t)) return 1.0;
            boolean dangerous = t.giving().propertyIds().stream()
                    .anyMatch(id -> StrongBotStrategy.wouldCompleteSet(ctx.state(), t.partnerPlayerId(), id));
            return ctx.params().curve("trade_gift_danger").eval(dangerous ? 0.0 : 1.0);
        }
    };

    static final List<Consideration> ACCEPT_CONSIDERATIONS = List.of(
            TRADE_CASH_AFFORDABILITY,
            TRADE_FAIRNESS_RATIO,
            TRADE_MONOPOLY_COMPLETION,
            TRADE_GIFT_DANGER
    );

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Face-price value of a trade selection (properties + cash + jail cards × 50). */
    static int totalValue(SessionState state, TradeSelectionState sel) {
        int propValue = sel.propertyIds().stream()
                .mapToInt(id -> {
                    try { return SpotType.valueOf(id).getIntegerProperty("price"); }
                    catch (Exception e) { return 0; }
                }).sum();
        return propValue + sel.moneyAmount() + sel.jailCardCount() * 50;
    }
}
