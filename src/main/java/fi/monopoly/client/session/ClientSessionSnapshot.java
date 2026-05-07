package fi.monopoly.client.session;

import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.session.SessionStatus;

/**
 * Client-visible snapshot of session availability, lifecycle state, and authoritative game state.
 *
 * <p>The {@code state} field carries the full authoritative {@link SessionState} so that a client
 * can reconstruct its local presentation model from a received snapshot without requiring a
 * separate query to the host. This is the enabling shape for the backend transport MVP: a remote
 * host can push this snapshot over the wire and the client applies it to its legacy runtime.</p>
 */
public record ClientSessionSnapshot(
        String sessionId,
        long version,
        SessionStatus status,
        boolean viewAvailable,
        SessionState state
) {
    public static ClientSessionSnapshot empty() {
        return new ClientSessionSnapshot(null, 0L, null, false, null);
    }

    public static ClientSessionSnapshot from(SessionState sessionState, boolean viewAvailable) {
        if (sessionState == null) {
            return empty();
        }
        return new ClientSessionSnapshot(
                sessionState.sessionId(),
                sessionState.version(),
                sessionState.status(),
                viewAvailable,
                sessionState
        );
    }
}
