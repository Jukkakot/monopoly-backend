package fi.monopoly.client.session;

/**
 * Client-facing session update stream for whichever host currently owns Monopoly session
 * authority.
 *
 * <p>The current desktop client still talks to an embedded local host in-process, but the client
 * no longer needs a host-shaped "session" object here. What it actually consumes is a stream of
 * authoritative session updates that can later come from either an embedded adapter or a remote
 * transport connection.</p>
 */
public interface ClientSessionUpdates {
    void addListener(ClientSessionListener listener);

    void removeListener(ClientSessionListener listener);
}
