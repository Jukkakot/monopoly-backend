package fi.monopoly.server.session;

import fi.monopoly.application.command.*;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.domain.session.DebtAction;
import fi.monopoly.domain.session.TradeEditPatch;
import fi.monopoly.server.bot.Intent;

import java.util.List;

/**
 * Phase 1.3: translates a {@link Intent} produced by a {@link fi.monopoly.server.bot.BotStrategy}
 * into one concrete {@link SessionCommand} and dispatches it through a
 * {@link SessionCommandPublisher}.
 *
 * <p>Separating translation from strategy enables the headless evaluation harness
 * (Phase 2) to inject a different "publisher" — one that drives the in-memory game
 * engine directly instead of going through the full SSE stack.</p>
 */
public final class BotExecutor {

    private final SessionCommandPublisher publisher;
    private final String sessionId;

    public BotExecutor(SessionCommandPublisher publisher, String sessionId) {
        this.publisher = publisher;
        this.sessionId = sessionId;
    }

    /**
     * Translate {@code intent} into exactly one command and dispatch it.
     *
     * @param intent the decision returned by the bot strategy
     * @param botId  the player submitting the command
     * @return the {@link CommandResult} from the command bus
     */
    public CommandResult execute(Intent intent, String botId) {
        return switch (intent) {
            case Intent.Roll()             -> publisher.handle(new RollDiceCommand(sessionId, botId));
            case Intent.AcknowledgeCard()  -> publisher.handle(new AcknowledgeCardCommand(sessionId, botId));
            case Intent.EndTurn()          -> publisher.handle(new EndTurnCommand(sessionId, botId));

            case Intent.BuildHouses(String propId) ->
                    publisher.handle(new BuyBuildingRoundCommand(sessionId, botId, propId));

            case Intent.Unmortgage(String propId) ->
                    publisher.handle(new ToggleMortgageCommand(sessionId, botId, propId));

            case Intent.MortgageProperty(String propId) ->
                    publisher.handle(new ToggleMortgageCommand(sessionId, botId, propId));

            case Intent.BuyProperty(String decisionId, String propId) ->
                    publisher.handle(new BuyPropertyCommand(sessionId, botId, decisionId, propId));

            case Intent.DeclineProperty(String decisionId, String propId) ->
                    publisher.handle(new DeclinePropertyCommand(sessionId, botId, decisionId, propId));

            case Intent.ProposeTrade(String partnerId) ->
                    publisher.handle(new OpenTradeCommand(sessionId, botId, partnerId));

            case Intent.RespondToTrade(Intent.TradeResponse resp, String tradeId) ->
                    switch (resp) {
                        case ACCEPT  -> publisher.handle(new AcceptTradeCommand(sessionId, botId, tradeId));
                        case DECLINE -> publisher.handle(new DeclineTradeCommand(sessionId, botId, tradeId));
                        case COUNTER -> publisher.handle(new CounterTradeCommand(sessionId, botId, tradeId));
                    };

            case Intent.EditTrade(String tradeId, TradeEditPatch patch) ->
                    publisher.handle(new EditTradeOfferCommand(sessionId, botId, tradeId, patch));

            case Intent.SubmitTrade(String tradeId) ->
                    publisher.handle(new SubmitTradeOfferCommand(sessionId, botId, tradeId));

            case Intent.CancelTrade(String tradeId) ->
                    publisher.handle(new CancelTradeCommand(sessionId, botId, tradeId));

            case Intent.Bid(String auctionId, long amount) ->
                    publisher.handle(new PlaceAuctionBidCommand(sessionId, botId, auctionId, (int) amount));

            case Intent.PassAuction(String auctionId) ->
                    publisher.handle(new PassAuctionCommand(sessionId, botId, auctionId));

            case Intent.FinishAuction(String auctionId) ->
                    publisher.handle(new FinishAuctionResolutionCommand(sessionId, auctionId));

            case Intent.ResolveDebt(String debtId, DebtAction action, String propId) ->
                    switch (action) {
                        case PAY_DEBT_NOW ->
                                publisher.handle(new PayDebtCommand(sessionId, botId, debtId));
                        case SELL_BUILDING ->
                                publisher.handle(new SellBuildingForDebtCommand(sessionId, botId, debtId, propId, 1));
                        case MORTGAGE_PROPERTY ->
                                publisher.handle(new MortgagePropertyForDebtCommand(sessionId, botId, debtId, propId));
                        default -> noOp();
                    };

            case Intent.DeclareBankruptcy(String debtId) ->
                    publisher.handle(new DeclareBankruptcyCommand(sessionId, botId, debtId));

            case Intent.NoOp() -> noOp();
        };
    }

    private CommandResult noOp() {
        return new CommandResult(false, publisher.currentState(), List.of(), List.of(), List.of());
    }
}
