package fi.monopoly.application.command;

public record BuyBuildingRoundCommand(
        String sessionId,
        String actorPlayerId,
        String propertyId
) implements SessionCommand {
}
