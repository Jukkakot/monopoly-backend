package fi.monopoly.application.command;

public record PayJailFineCommand(
        String sessionId,
        String actorPlayerId
) implements SessionCommand {
}
