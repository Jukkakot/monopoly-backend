package fi.monopoly.application.command;

public record DeclinePropertyCommand(
        String sessionId,
        String actorPlayerId,
        String decisionId,
        String propertyId
) implements SessionCommand {
}
