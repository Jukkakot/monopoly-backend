package fi.monopoly.application.command;

public record RefreshSessionViewCommand(String sessionId) implements SessionCommand {
}
