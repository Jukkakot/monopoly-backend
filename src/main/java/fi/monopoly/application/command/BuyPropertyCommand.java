package fi.monopoly.application.command;

public record BuyPropertyCommand(
        String sessionId,
        String actorPlayerId,
        String decisionId,
        String propertyId
) implements SessionCommand {
}
