package fi.monopoly.server.session;

import fi.monopoly.application.command.SessionCommand;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.client.session.ClientSessionListener;
import fi.monopoly.client.session.ClientSessionUpdates;
import fi.monopoly.client.session.SessionCommandPort;
import fi.monopoly.domain.session.BotDifficulty;
import fi.monopoly.domain.session.SeatKind;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.server.transport.SessionHttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

/**
 * Embedded HTTP session server with a {@link SessionRegistry} for multi-session support.
 *
 * <p>Starts on an auto-detected free port. Single-session backward-compat endpoints are wired to
 * no-op stubs — only the {@code /sessions} multi-session API is active. Call
 * {@link #create(List, List)} to create pure-domain sessions, then connect clients using
 * {@link #baseUrl()} combined with the returned session ID.</p>
 */
@Slf4j
public final class EmbeddedSessionServer {

    private final int port;
    private final SessionRegistry registry;
    private final SessionHttpServer httpServer;

    private EmbeddedSessionServer(int port) throws IOException {
        this.port = port;
        this.registry = new SessionRegistry();
        this.httpServer = new SessionHttpServer(
                new NoopCommandPort(), new NoopUpdates(), () -> null, port, registry);
        httpServer.start();
    }

    /**
     * Starts an embedded server on any available port.
     */
    public static EmbeddedSessionServer start() throws IOException {
        int port = findFreePort();
        EmbeddedSessionServer server = new EmbeddedSessionServer(port);
        log.info("Embedded session server started — http://localhost:{}/sessions", port);
        return server;
    }

    /**
     * Creates a new all-human pure-domain session and returns its session ID.
     */
    public String create(List<String> playerNames, List<String> colors) {
        return create(playerNames, colors, List.of());
    }

    /**
     * Creates a new pure-domain session with explicit seat kinds and returns its session ID.
     * Bot seats will have a {@link PureDomainBotDriver} attached automatically.
     */
    public String create(List<String> playerNames, List<String> colors, List<SeatKind> seatKinds) {
        return create(playerNames, colors, seatKinds, List.of());
    }

    /**
     * Creates a session with explicit seat kinds and per-seat bot difficulties.
     */
    public String create(List<String> playerNames, List<String> colors, List<SeatKind> seatKinds,
                         List<BotDifficulty> difficulties) {
        String sessionId = registry.create(playerNames, colors, seatKinds, difficulties);
        log.info("Created session {} with players {} seatKinds={} difficulties={}",
                sessionId.substring(0, 8), playerNames, seatKinds, difficulties);
        return sessionId;
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://localhost:" + port;
    }

    public void stop() {
        registry.shutdown();
        httpServer.stop();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class NoopCommandPort implements SessionCommandPort {
        @Override
        public CommandResult handle(SessionCommand command) {
            throw new UnsupportedOperationException("Use /sessions API for multi-session commands");
        }

        @Override
        public SessionState currentState() {
            return null;
        }
    }

    private static final class NoopUpdates implements ClientSessionUpdates {
        @Override
        public void addListener(ClientSessionListener listener) {}

        @Override
        public void removeListener(ClientSessionListener listener) {}
    }
}
