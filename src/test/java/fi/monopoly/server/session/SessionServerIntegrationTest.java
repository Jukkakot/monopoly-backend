package fi.monopoly.server.session;

import fi.monopoly.application.command.SessionCommand;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.client.session.ClientSessionListener;
import fi.monopoly.client.session.ClientSessionSnapshot;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: command submitted via HTTP reaches the publisher, snapshot
 * is served back, and accepted commands trigger SSE listener notification.
 */
class SessionServerIntegrationTest {

    private int port;
    private StubCommandPort stub;
    private SessionCommandPublisher publisher;
    private SessionServer server;

    @BeforeEach
    void setUp() throws IOException {
        port = findFreePort();
        stub = new StubCommandPort();
        publisher = new SessionCommandPublisher(stub);
        server = new SessionServer(publisher, publisher, publisher::currentSnapshot, port);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void snapshotEndpointReturnsPublisherSnapshot() throws Exception {
        HttpResponse<String> response = get("/snapshot");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"sessionId\":\"integration-session\""),
                "Expected sessionId in snapshot response, got: " + response.body());
    }

    @Test
    void acceptedCommandViaHttpNotifiesListeners() throws Exception {
        List<ClientSessionSnapshot> received = new CopyOnWriteArrayList<>();
        publisher.addListener(received::add);

        stub.nextAccepted = true;
        String json = "{\"type\":\"RollDice\",\"sessionId\":\"integration-session\",\"actorPlayerId\":\"p1\"}";
        HttpResponse<String> response = post("/command", json);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"accepted\":true"));
        assertFalse(received.isEmpty(), "Expected listener to receive snapshot after accepted command");
        assertEquals("integration-session", received.getFirst().sessionId());
    }

    @Test
    void rejectedCommandViaHttpDoesNotNotifyListeners() throws Exception {
        List<ClientSessionSnapshot> received = new ArrayList<>();
        publisher.addListener(received::add);

        stub.nextAccepted = false;
        String json = "{\"type\":\"RollDice\",\"sessionId\":\"integration-session\",\"actorPlayerId\":\"p1\"}";
        HttpResponse<String> response = post("/command", json);

        assertEquals(422, response.statusCode());
        assertTrue(received.isEmpty(), "Expected no listener notification after rejected command");
    }

    @Test
    void healthEndpointResponds() throws Exception {
        HttpResponse<String> response = get("/health");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("ok"));
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

    private static SessionState minimalState() {
        return SessionState.builder()
                .sessionId("integration-session")
                .version(1L)
                .status(SessionStatus.IN_PROGRESS)
                .seats(List.of())
                .players(List.of())
                .properties(List.of())
                .build();
    }

    private static class StubCommandPort implements SessionCommandPort {
        boolean nextAccepted = true;

        @Override
        public CommandResult handle(SessionCommand command) {
            return new CommandResult(nextAccepted, minimalState(), List.of(), List.of(), List.of());
        }

        @Override
        public SessionState currentState() {
            return minimalState();
        }
    }
}
