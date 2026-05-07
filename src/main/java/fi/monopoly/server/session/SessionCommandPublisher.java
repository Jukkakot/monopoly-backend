package fi.monopoly.server.session;

import fi.monopoly.application.command.SessionCommand;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.client.session.ClientSessionListener;
import fi.monopoly.client.session.ClientSessionSnapshot;
import fi.monopoly.client.session.ClientSessionUpdates;
import fi.monopoly.client.session.SessionCommandPort;
import fi.monopoly.domain.session.SessionState;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Decorates a {@link SessionCommandPort} with snapshot publication semantics.
 *
 * <p>After each accepted command, all registered {@link ClientSessionListener}s receive the
 * updated snapshot. This makes the publisher the event-emission hub for a standalone session
 * host: the command processing pipeline does not need to know about transport concerns, and
 * SSE / WebSocket push layers only need to register a listener here.</p>
 *
 * <p>This class implements two interfaces simultaneously so that a single publisher instance
 * can be passed to {@link SessionServer} as both the command port and the update stream:</p>
 * <pre>
 *   SessionCommandPublisher publisher = new SessionCommandPublisher(service);
 *   SessionServer server = new SessionServer(publisher, publisher,
 *           publisher::currentSnapshot, port);
 * </pre>
 *
 * <p><b>Thread safety:</b> listener list is a {@link CopyOnWriteArrayList}; individual
 * notifications fire sequentially on the calling thread. The underlying
 * {@link SessionCommandPort} is not thread-safe — callers must serialize command
 * submission externally (e.g. via the virtual-thread-per-request HTTP server where each
 * request runs on its own thread, combined with a synchronized {@code handle()} if needed).</p>
 */
@Slf4j
public final class SessionCommandPublisher implements SessionCommandPort, ClientSessionUpdates {

    private final SessionCommandPort delegate;
    private final List<ClientSessionListener> listeners = new CopyOnWriteArrayList<>();

    public SessionCommandPublisher(SessionCommandPort delegate) {
        this.delegate = delegate;
    }

    @Override
    public synchronized CommandResult handle(SessionCommand command) {
        CommandResult result = delegate.handle(command);
        if (result.accepted()) {
            publishSnapshot();
        }
        return result;
    }

    @Override
    public SessionState currentState() {
        return delegate.currentState();
    }

    public ClientSessionSnapshot currentSnapshot() {
        return ClientSessionSnapshot.from(delegate.currentState(), true);
    }

    @Override
    public void addListener(ClientSessionListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(ClientSessionListener listener) {
        listeners.remove(listener);
    }

    private void publishSnapshot() {
        ClientSessionSnapshot snapshot = currentSnapshot();
        for (ClientSessionListener listener : listeners) {
            try {
                listener.onSnapshotChanged(snapshot);
            } catch (Exception e) {
                log.warn("Listener threw during snapshot publish", e);
            }
        }
    }
}
