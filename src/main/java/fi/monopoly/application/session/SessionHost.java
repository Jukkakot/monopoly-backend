package fi.monopoly.application.session;

import fi.monopoly.domain.session.SessionState;

/**
 * Small host seam for whichever runtime currently owns the authoritative local session instance.
 *
 * <p>The current desktop app uses this to let local save/load read the latest session state and
 * replace the active game when a snapshot is restored. The same abstraction is intended to stay
 * valid when session ownership later moves behind a dedicated backend host.</p>
 */
public interface SessionHost {
    SessionState currentState();

    void replaceState(SessionState restoredState);
}
