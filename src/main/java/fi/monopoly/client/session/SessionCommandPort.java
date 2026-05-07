package fi.monopoly.client.session;

import fi.monopoly.application.command.SessionCommand;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.domain.session.SessionState;

/**
 * Transport-neutral command submission and state query seam used by presentation-layer adapters.
 *
 * <p>The embedded desktop host implements this via {@code SessionApplicationService}. A future
 * remote host implementation will implement the same interface through a network transport,
 * which is why the adapters must not depend on the full {@code SessionApplicationService}.</p>
 */
public interface SessionCommandPort {
    CommandResult handle(SessionCommand command);

    SessionState currentState();
}
