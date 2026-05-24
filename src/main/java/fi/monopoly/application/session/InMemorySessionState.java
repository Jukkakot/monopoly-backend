package fi.monopoly.application.session;

import fi.monopoly.domain.session.SessionState;

import java.util.function.UnaryOperator;

/**
 * In-memory {@link SessionStateStore} backed by a single {@link SessionState} reference.
 *
 * <p>Used by pure domain session factories that maintain authoritative state in-process
 * without delegating to legacy runtime objects. All updates are atomic (synchronized).</p>
 *
 * <p>An optional {@code onChange} callback is invoked after each update, outside the
 * synchronized block, so each intermediate state during a command (e.g. player moving to
 * a square before jail logic runs) is published to SSE clients individually.</p>
 */
public final class InMemorySessionState implements SessionStateStore {
    private volatile SessionState state;
    private volatile Runnable onChange;

    public InMemorySessionState(SessionState initialState) {
        this.state = initialState;
    }

    /** Called after every {@link #update}, outside the lock, with the new state visible. */
    public void setOnChange(Runnable fn) {
        this.onChange = fn;
    }

    @Override
    public SessionState get() {
        return state;
    }

    @Override
    public void update(UnaryOperator<SessionState> mutator) {
        synchronized (this) {
            SessionState next = mutator.apply(state);
            state = next.toBuilder().version(state.version() + 1).build();
        }
        Runnable fn = onChange;
        if (fn != null) fn.run();
    }
}
