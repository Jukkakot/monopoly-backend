package fi.monopoly.application.command;

public record SellBuildingForDebtCommand(
        String sessionId,
        String actorPlayerId,
        String debtId,
        String propertyId,
        int count
) implements SessionCommand {
}
