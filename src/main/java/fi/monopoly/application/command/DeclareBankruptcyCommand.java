package fi.monopoly.application.command;

public record DeclareBankruptcyCommand(
        String sessionId,
        String actorPlayerId,
        String debtId
) implements SessionCommand {
}
