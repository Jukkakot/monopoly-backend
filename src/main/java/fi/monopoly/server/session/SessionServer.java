package fi.monopoly.server.session;

import fi.monopoly.client.session.ClientSessionSnapshot;
import fi.monopoly.client.session.ClientSessionUpdates;
import fi.monopoly.client.session.SessionCommandPort;
import fi.monopoly.server.transport.SessionHttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Lifecycle wrapper around {@link SessionHttpServer} for the server-side session host.
 *
 * <p>Both the current embedded desktop mode and the future standalone server process use
 * {@link SessionHttpServer} as the HTTP layer. This class owns start/stop and the JVM shutdown
 * hook so that the caller (embedded binding factory or standalone {@code main}) only needs to
 * provide the three session seams and a port number.</p>
 *
 * <p>Intended usage path:</p>
 * <pre>
 *   // embedded (current):
 *   SessionServer server = new SessionServer(embeddedHost, embeddedHost,
 *           embeddedHost::currentSnapshot, port);
 *   server.start();
 *   server.registerShutdownHook();
 *
 *   // standalone (future — once pure domain gateways exist):
 *   SessionApplicationService service = PureDomainSessionFactory.create(sessionId);
 *   SessionCommandPublisher publisher = new SessionCommandPublisher(service);
 *   SessionServer server = new SessionServer(publisher, publisher,
 *           publisher::currentSnapshot, port);
 *   server.start();
 *   server.registerShutdownHook();
 * </pre>
 */
@Slf4j
public final class SessionServer {

    private final SessionHttpServer httpServer;

    public SessionServer(
            SessionCommandPort commandPort,
            ClientSessionUpdates sessionUpdates,
            Supplier<ClientSessionSnapshot> snapshotSupplier,
            int port
    ) {
        this.httpServer = new SessionHttpServer(commandPort, sessionUpdates, snapshotSupplier, port);
    }

    public void start() throws IOException {
        httpServer.start();
        log.info("Session server started on port {}", httpServer.port());
    }

    public void stop() {
        httpServer.stop();
    }

    public int port() {
        return httpServer.port();
    }

    /**
     * Registers a JVM shutdown hook that stops the HTTP server cleanly.
     * Call once after {@link #start()} to ensure graceful shutdown on Ctrl-C or SIGTERM.
     */
    public void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(
                Thread.ofVirtual().name("session-server-shutdown").unstarted(this::stop));
    }
}
