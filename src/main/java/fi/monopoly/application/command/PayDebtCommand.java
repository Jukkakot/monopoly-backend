package fi.monopoly.application.command;

public record PayDebtCommand(
        String sessionId,
        String actorPlayerId,
        String debtId
) implements SessionCommand {
}
