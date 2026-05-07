package fi.monopoly.application.command;

public record EndTurnCommand(
        String sessionId,
        String actorPlayerId
) implements SessionCommand {
}
