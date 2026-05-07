package fi.monopoly.application.command;

public record SellBuildingRoundsAcrossSetForDebtCommand(
        String sessionId,
        String actorPlayerId,
        String debtId,
        String propertyId,
        int rounds
) implements SessionCommand {
}
