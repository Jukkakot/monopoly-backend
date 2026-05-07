package fi.monopoly.application.command;

public record AcceptTradeCommand(
        String sessionId,
        String actorPlayerId,
        String tradeId
) implements SessionCommand {
}
