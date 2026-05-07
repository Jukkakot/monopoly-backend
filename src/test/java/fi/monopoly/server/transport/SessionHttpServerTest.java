package fi.monopoly.server.transport;

import fi.monopoly.application.command.RollDiceCommand;
import fi.monopoly.application.command.SessionCommand;
import fi.monopoly.application.result.CommandRejection;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.client.session.ClientSessionListener;
import fi.monopoly.client.session.ClientSessionSnapshot;
import fi.monopoly.client.session.ClientSessionUpdates;
import fi.monopoly.client.session.SessionCommandPort;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.session.SessionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SessionHttpServerTest {

    private int port;
    private SessionHttpServer server;
    private StubSessionUpdates sessionUpdates;
    private StubCommandPort commandPort;
    private ClientSessionSnapshot fixedSnapshot;

    @BeforeEach
    void setUp() throws IOException {
        port = findFreePort();
        commandPort = new StubCommandPort();
        sessionUpdates = new StubSessionUpdates();
        fixedSnapshot = new ClientSessionSnapshot("s1", 1L, SessionStatus.IN_PROGRESS, false, null);
        server = new SessionHttpServer(commandPort, sessionUpdates, () -> fixedSnapshot, port);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void healthEndpoint() throws Exception {
        HttpResponse<String> response = get("/health");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("ok"));
    }

    @Test
    void snapshotEndpoint_returnsCurrentSnapshot() throws Exception {
        HttpResponse<String> response = get("/snapshot");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"sessionId\":\"s1\""));
        assertTrue(response.body().contains("\"version\":1"));
    }

    @Test
    void commandEndpoint_acceptedCommand_returns200() throws Exception {
        commandPort.nextResult = new CommandResult(true, null, List.of(), List.of(), List.of());
        String json = "{\"type\":\"RollDice\",\"sessionId\":\"s1\",\"actorPlayerId\":\"p1\"}";
        HttpResponse<String> response = post("/command", json);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"accepted\":true"));
        assertNotNull(commandPort.lastCommand);
        assertInstanceOf(RollDiceCommand.class, commandPort.lastCommand);
    }

    @Test
    void commandEndpoint_rejectedCommand_returns422() throws Exception {
        commandPort.nextResult = new CommandResult(false, null, List.of(),
                List.of(new CommandRejection("NOT_YOUR_TURN", "Not your turn")), List.of());
        String json = "{\"type\":\"RollDice\",\"sessionId\":\"s1\",\"actorPlayerId\":\"p2\"}";
        HttpResponse<String> response = post("/command", json);
        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("\"accepted\":false"));
    }

    @Test
    void commandEndpoint_unknownType_returns400() throws Exception {
        String json = "{\"type\":\"UnknownCommand\",\"sessionId\":\"s1\"}";
        HttpResponse<String> response = post("/command", json);
        assertEquals(400, response.statusCode());
    }

    @Test
    void commandEndpoint_wrongMethod_returns404or405() throws Exception {
        HttpResponse<String> response = get("/command");
        // Javalin returns 404 for unmatched routes (no automatic 405 for wrong method)
        assertTrue(response.statusCode() == 404 || response.statusCode() == 405);
    }

    @Test
    void noSharedListenerRegisteredAtStartup() {
        // Per-connection listeners are added only when a client opens /events — not at start.
        assertEquals(0, sessionUpdates.listeners.size());
    }

    @Test
    void corsHeadersPresentOnSnapshotResponse() throws Exception {
        HttpResponse<String> response = get("/snapshot");
        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertNotNull(response.headers().firstValue("Access-Control-Allow-Methods").orElse(null));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    // -------------------------------------------------------------------------
    // Stubs
    // -------------------------------------------------------------------------

    static final class StubCommandPort implements SessionCommandPort {
        CommandResult nextResult = new CommandResult(true, null, List.of(), List.of(), List.of());
        SessionCommand lastCommand;

        @Override
        public CommandResult handle(SessionCommand command) {
            lastCommand = command;
            return nextResult;
        }

        @Override
        public SessionState currentState() {
            return null;
        }
    }

    static final class StubSessionUpdates implements ClientSessionUpdates {
        final List<ClientSessionListener> listeners = new ArrayList<>();

        @Override
        public void addListener(ClientSessionListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(ClientSessionListener listener) {
            listeners.remove(listener);
        }
    }
}
