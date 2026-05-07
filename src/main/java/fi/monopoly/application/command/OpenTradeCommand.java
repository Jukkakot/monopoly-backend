package fi.monopoly.application.command;

public record OpenTradeCommand(
        String sessionId,
        String actorPlayerId,
        String recipientPlayerId
) implements SessionCommand {
}
