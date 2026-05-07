package fi.monopoly.application.session;

import fi.monopoly.domain.session.SessionState;

import java.util.function.UnaryOperator;

/**
 * In-memory {@link SessionStateStore} backed by a single {@link SessionState} reference.
 *
 * <p>Used by pure domain session factories that maintain authoritative state in-process
 * without delegating to legacy runtime objects. All updates are atomic (synchronized).</p>
 */
public final class InMemorySessionState implements SessionStateStore {
    private volatile SessionState state;

    public InMemorySessionState(SessionState initialState) {
        this.state = initialState;
    }

    @Override
    public SessionState get() {
        return state;
    }

    @Override
    public synchronized void update(UnaryOperator<SessionState> mutator) {
        state = mutator.apply(state);
    }
}
