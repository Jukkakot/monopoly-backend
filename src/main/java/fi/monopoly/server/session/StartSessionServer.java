package fi.monopoly.server.session;

import fi.monopoly.application.command.SessionCommand;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.application.session.SessionApplicationService;
import fi.monopoly.client.session.ClientSessionListener;
import fi.monopoly.client.session.ClientSessionUpdates;
import fi.monopoly.client.session.SessionCommandPort;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.server.transport.SessionHttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Entry point for the standalone session server process.
 *
 * <h2>Empty-registry mode (no player names)</h2>
 * <pre>
 *   java -cp monopoly.jar fi.monopoly.server.session.StartSessionServer [port]
 * </pre>
 * <p>Starts an empty server. Sessions are created on demand via {@code POST /sessions}.</p>
 *
 * <h2>Single-session mode (with player names)</h2>
 * <pre>
 *   java -cp monopoly.jar fi.monopoly.server.session.StartSessionServer [port] Pelaaja1 Pelaaja2
 * </pre>
 * <p>Creates one session immediately and exposes it via the backward-compatible
 * {@code /command}, {@code /snapshot}, {@code /events} endpoints.
 * The {@code /sessions} API is also available for creating additional sessions.</p>
 *
 * <p>Default port is 8080.</p>
 *
 * @see SessionHttpServer
 */
@Slf4j
public final class StartSessionServer {

    private StartSessionServer() {}

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        if (args.length <= 1) {
            startRegistryMode(port);
        } else {
            List<String> playerNames = List.of(args).subList(1, args.length);
            startSingleSessionMode(port, playerNames);
        }
    }

    // -------------------------------------------------------------------------
    // Startup modes
    // -------------------------------------------------------------------------

    private static void startRegistryMode(int port) throws IOException {
        SessionRegistry registry = new SessionRegistry();

        // The single-session backward-compat endpoints are unused in registry-only mode.
        // Provide stubs so SessionHttpServer compiles; they are never called in practice.
        SessionCommandPort noopCommand = new SessionCommandPort() {
            @Override public CommandResult handle(SessionCommand command) {
                throw new UnsupportedOperationException("Use /sessions API");
            }
            @Override public SessionState currentState() {
                throw new UnsupportedOperationException("Use /sessions API");
            }
        };
        ClientSessionUpdates noopUpdates = new ClientSessionUpdates() {
            @Override public void addListener(ClientSessionListener l) {}
            @Override public void removeListener(ClientSessionListener l) {}
        };

        SessionHttpServer httpServer = new SessionHttpServer(
                noopCommand, noopUpdates, () -> { throw new UnsupportedOperationException(); }, port, registry);
        httpServer.start();
        registerShutdownHook(httpServer);

        log.info("Standalone session server started in registry mode — port={}", port);
        log.info("Create sessions via: POST http://localhost:{}/sessions", port);
    }

    private static void startSingleSessionMode(int port, List<String> playerNames) throws IOException {
        List<String> colors = List.of("#E63946", "#2A9D8F", "#E9C46A", "#264653");

        String sessionId = UUID.randomUUID().toString();
        SessionState initialState = PureDomainSessionFactory.initialGameState(sessionId, playerNames, colors);
        SessionApplicationService service = PureDomainSessionFactory.create(sessionId, initialState);
        SessionCommandPublisher publisher = new SessionCommandPublisher(service);

        SessionRegistry registry = new SessionRegistry();
        SessionHttpServer httpServer = new SessionHttpServer(
                publisher, publisher, publisher::currentSnapshot, port, registry);
        httpServer.start();
        registerShutdownHook(httpServer);

        log.info("Standalone session server started — sessionId={} port={} players={}",
                sessionId, port, playerNames);
    }

    private static void registerShutdownHook(SessionHttpServer httpServer) {
        Runtime.getRuntime().addShutdownHook(
                Thread.ofVirtual().name("session-server-shutdown").unstarted(httpServer::stop));
    }
}
