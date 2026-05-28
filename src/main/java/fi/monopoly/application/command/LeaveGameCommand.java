package fi.monopoly.application.command;

public record LeaveGameCommand(
        String sessionId,
        String actorPlayerId
) implements SessionCommand {
}
