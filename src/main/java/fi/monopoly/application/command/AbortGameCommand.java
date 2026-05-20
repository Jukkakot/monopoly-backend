package fi.monopoly.application.command;

public record AbortGameCommand(
        String sessionId,
        String actorPlayerId
) implements SessionCommand {
}
