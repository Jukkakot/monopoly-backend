package fi.monopoly.application.command;

import fi.monopoly.domain.session.TradeEditPatch;

public record EditTradeOfferCommand(
        String sessionId,
        String actorPlayerId,
        String tradeId,
        TradeEditPatch patch
) implements SessionCommand {
}
