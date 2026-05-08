package fi.monopoly.application.command;

public record UseGetOutOfJailCardCommand(
        String sessionId,
        String actorPlayerId
) implements SessionCommand {
}
