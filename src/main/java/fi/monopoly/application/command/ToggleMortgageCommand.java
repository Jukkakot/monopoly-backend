package fi.monopoly.application.command;

public record ToggleMortgageCommand(
        String sessionId,
        String actorPlayerId,
        String propertyId
) implements SessionCommand {
}
