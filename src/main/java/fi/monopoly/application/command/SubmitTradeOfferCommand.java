package fi.monopoly.application.command;

public record SubmitTradeOfferCommand(
        String sessionId,
        String actorPlayerId,
        String tradeId
) implements SessionCommand {
}
