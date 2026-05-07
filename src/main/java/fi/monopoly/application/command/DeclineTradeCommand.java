package fi.monopoly.application.command;

public record DeclineTradeCommand(
        String sessionId,
        String actorPlayerId,
        String tradeId
) implements SessionCommand {
}
