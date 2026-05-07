package fi.monopoly.application.command;

public record CancelTradeCommand(
        String sessionId,
        String actorPlayerId,
        String tradeId
) implements SessionCommand {
}
