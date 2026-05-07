package fi.monopoly.client.session;

import fi.monopoly.application.command.SessionCommand;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.domain.session.SessionState;

import java.util.Objects;

/**
 * Mutable proxy for {@link SessionCommandPort} that lets the command delegate be wired after
 * construction.
 *
 * <p>Commands are forwarded to the currently-set {@code commandDelegate}. State queries always
 * go to the original {@code stateSource} passed at construction time, so they never create a
 * cycle back through the host's own {@code currentState()} path.</p>
 *
 * <p>This lets the presentation-layer session adapters be assembled (and the proxy given to them)
 * before the embedded session host is available, and then have the host wired in via
 * {@link #setCommandDelegate(SessionCommandPort)} once it exists.</p>
 */
public final class ForwardingSessionCommandPort implements SessionCommandPort {

    private final SessionCommandPort stateSource;
    private SessionCommandPort commandDelegate;

    public ForwardingSessionCommandPort(SessionCommandPort initialDelegate) {
        this.stateSource = Objects.requireNonNull(initialDelegate);
        this.commandDelegate = initialDelegate;
    }

    public void setCommandDelegate(SessionCommandPort delegate) {
        this.commandDelegate = Objects.requireNonNull(delegate);
    }

    @Override
    public CommandResult handle(SessionCommand command) {
        return commandDelegate.handle(command);
    }

    @Override
    public SessionState currentState() {
        return stateSource.currentState();
    }
}
