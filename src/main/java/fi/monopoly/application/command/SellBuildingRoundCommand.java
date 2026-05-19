package fi.monopoly.application.command;

public record SellBuildingRoundCommand(
        String sessionId,
        String actorPlayerId,
        String propertyId
) implements SessionCommand {
}
