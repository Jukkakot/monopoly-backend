package fi.monopoly.application.command;

public interface SessionCommand {
    String sessionId();

    default String actorPlayerId() {
        return null;
    }
}
