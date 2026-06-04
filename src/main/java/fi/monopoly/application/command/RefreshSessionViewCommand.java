package fi.monopoly.application.command;

/**
 * No-op command that triggers a snapshot re-publication to all SSE listeners.
 *
 * <p>Originally used by the Processing desktop client to force a UI refresh after certain
 * local operations. In the HTTP server it is handled as {@code accepted()} which automatically
 * re-publishes the current state — so the command still works, it just does nothing extra.</p>
 */
public record RefreshSessionViewCommand(String sessionId) implements SessionCommand {
}
