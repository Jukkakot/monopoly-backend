package fi.monopoly.application.session;

import fi.monopoly.domain.session.SessionState;

import java.util.function.UnaryOperator;

/**
 * Read/write access to the authoritative {@link SessionState} for pure domain gateway
 * implementations that must update game state without delegating to legacy runtime objects.
 */
public interface SessionStateStore {
    SessionState get();

    void update(UnaryOperator<SessionState> mutator);
}
