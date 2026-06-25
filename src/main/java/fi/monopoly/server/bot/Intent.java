package fi.monopoly.server.bot;

import fi.monopoly.domain.session.DebtAction;

import java.util.List;

/**
 * Phase 1.1: sealed Intent algebra — the complete set of decisions a bot can make.
 *
 * <p>An {@code Intent} is a pure value returned by {@link BotStrategy#decide}. The
 * {@link BotExecutor} translates it into one or more game commands and applies them.
 * Every case that currently appears in {@code PureDomainBotDriver.dispatchGreedy} and its
 * sub-methods maps to exactly one variant here.</p>
 *
 * <h2>Completeness audit (Phase 1.1 requirement)</h2>
 * <ul>
 *   <li>{@code WAITING_FOR_ROLL}          → {@link Roll}</li>
 *   <li>{@code WAITING_FOR_CARD_ACK}      → {@link AcknowledgeCard}</li>
 *   <li>{@code WAITING_FOR_END_TURN}      → {@link BuildHouses}, {@link Unmortgage},
 *       {@link ProposeTrade}, {@link EndTurn}</li>
 *   <li>{@code WAITING_FOR_DECISION} / property purchase → {@link BuyProperty},
 *       {@link DeclineProperty}</li>
 *   <li>{@code WAITING_FOR_DECISION} / trade response   → {@link RespondToTrade}</li>
 *   <li>{@code RESOLVING_DEBT}            → {@link ResolveDebt}, {@link DeclareBankruptcy}</li>
 *   <li>{@code WAITING_FOR_AUCTION}       → {@link Bid}, {@link PassAuction},
 *       {@link FinishAuction}</li>
 *   <li>Trade editing (EDITING / COUNTERED) → {@link EditTrade}, {@link SubmitTrade},
 *       {@link CancelTrade}</li>
 * </ul>
 */
public sealed interface Intent permits
        Intent.Roll,
        Intent.UseGetOutOfJailCard,
        Intent.PayJailFine,
        Intent.AcknowledgeCard,
        Intent.EndTurn,
        Intent.BuildHouses,
        Intent.Unmortgage,
        Intent.MortgageProperty,
        Intent.BuyProperty,
        Intent.DeclineProperty,
        Intent.ProposeTrade,
        Intent.RespondToTrade,
        Intent.EditTrade,
        Intent.SubmitTrade,
        Intent.CancelTrade,
        Intent.Bid,
        Intent.PassAuction,
        Intent.FinishAuction,
        Intent.ResolveDebt,
        Intent.DeclareBankruptcy,
        Intent.NoOp {

    // -------------------------------------------------------------------------
    // Turn management
    // -------------------------------------------------------------------------

    record Roll() implements Intent {}

    record UseGetOutOfJailCard() implements Intent {}

    record PayJailFine() implements Intent {}

    record AcknowledgeCard() implements Intent {}

    record EndTurn() implements Intent {}

    // -------------------------------------------------------------------------
    // WAITING_FOR_END_TURN management actions
    // -------------------------------------------------------------------------

    record BuildHouses(String propertyId) implements Intent {}

    record Unmortgage(String propertyId) implements Intent {}

    /** Mortgage an owned property (to raise cash for a purchase, distinct from debt resolution). */
    record MortgageProperty(String propertyId) implements Intent {}

    // -------------------------------------------------------------------------
    // Property purchase (WAITING_FOR_DECISION)
    // -------------------------------------------------------------------------

    record BuyProperty(String decisionId, String propertyId) implements Intent {}

    record DeclineProperty(String decisionId, String propertyId) implements Intent {}

    // -------------------------------------------------------------------------
    // Trades — HIGH LEVEL (Executor expands to multi-command protocol)
    // -------------------------------------------------------------------------

    /** Open + fill + submit a trade with {@code partnerId}. */
    record ProposeTrade(String partnerId) implements Intent {}

    /**
     * Respond to a received (or countered) trade offer.
     * {@code counterPartnerId} is non-null only for {@link TradeResponse#COUNTER}.
     */
    record RespondToTrade(TradeResponse response, String tradeId) implements Intent {}

    enum TradeResponse { ACCEPT, DECLINE, COUNTER }

    /** Edit one field of a trade offer currently in EDITING or COUNTERED state. */
    record EditTrade(String tradeId,
                     fi.monopoly.domain.session.TradeEditPatch patch) implements Intent {}

    record SubmitTrade(String tradeId) implements Intent {}

    record CancelTrade(String tradeId) implements Intent {}

    // -------------------------------------------------------------------------
    // Auction (WAITING_FOR_AUCTION)
    // -------------------------------------------------------------------------

    record Bid(String auctionId, long amount) implements Intent {}

    record PassAuction(String auctionId) implements Intent {}

    record FinishAuction(String auctionId) implements Intent {}

    // -------------------------------------------------------------------------
    // Debt resolution (RESOLVING_DEBT)
    // -------------------------------------------------------------------------

    record ResolveDebt(String debtId, DebtAction action, String propertyId) implements Intent {}

    record DeclareBankruptcy(String debtId) implements Intent {}

    // -------------------------------------------------------------------------
    // Fallback
    // -------------------------------------------------------------------------

    record NoOp() implements Intent {}
}
