package fi.monopoly.application.command;

public record MortgagePropertyForDebtCommand(
        String sessionId,
        String actorPlayerId,
        String debtId,
        String propertyId
) implements SessionCommand {
}
