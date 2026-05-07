package fi.monopoly.application.command;

public record RollDiceCommand(
        String sessionId,
        String actorPlayerId
) implements SessionCommand {
}
