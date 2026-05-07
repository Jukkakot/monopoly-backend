package fi.monopoly.client.session;

/**
 * Listener for client-visible session lifecycle and snapshot changes.
 */
@FunctionalInterface
public interface ClientSessionListener {
    void onSnapshotChanged(ClientSessionSnapshot snapshot);
}
