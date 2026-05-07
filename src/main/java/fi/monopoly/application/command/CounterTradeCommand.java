package fi.monopoly.application.command;

public record CounterTradeCommand(
        String sessionId,
        String actorPlayerId,
        String tradeId
) implements SessionCommand {
}
