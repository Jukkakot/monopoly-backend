package fi.monopoly.client.session.desktop;

import fi.monopoly.client.session.ClientSessionListener;
import fi.monopoly.client.session.ClientSessionSnapshot;

/**
 * Client-owned desktop session model fed by the host snapshot listener stream.
 *
 * <p>This gives the desktop client its own stable session state holder instead of forcing callers
 * to poll raw snapshot getters through the runtime adapter. It is the first small step toward a
 * real client-side presenter/projection layer above the transport-neutral session seam.</p>
 */
public final class DesktopClientSessionModel implements ClientSessionListener {
    private ClientSessionSnapshot snapshot = ClientSessionSnapshot.empty();

    public ClientSessionSnapshot currentSnapshot() {
        return snapshot;
    }

    public boolean hasActiveSession() {
        return snapshot.sessionId() != null;
    }

    public boolean viewAvailable() {
        return snapshot.viewAvailable();
    }

    public fi.monopoly.domain.session.SessionState sessionState() {
        return snapshot.state();
    }

    @Override
    public void onSnapshotChanged(ClientSessionSnapshot snapshot) {
        this.snapshot = snapshot != null ? snapshot : ClientSessionSnapshot.empty();
    }
}
