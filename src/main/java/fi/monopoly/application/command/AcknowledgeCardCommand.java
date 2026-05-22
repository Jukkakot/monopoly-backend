package fi.monopoly.application.command;

public record AcknowledgeCardCommand(
        String sessionId,
        String actorPlayerId
) implements SessionCommand {
}
