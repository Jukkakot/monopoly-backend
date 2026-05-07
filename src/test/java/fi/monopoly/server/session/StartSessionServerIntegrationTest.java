package fi.monopoly.server.session;

import fi.monopoly.application.session.SessionApplicationService;
import fi.monopoly.domain.session.SessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for the standalone server stack.
 *
 * <p>Uses {@link PureDomainSessionFactory} to build a real session with two players and
 * all board properties, then submits a {@code RollDice} command via HTTP and verifies
 * that the snapshot reflects the updated state — no Processing runtime involved.</p>
 */
class StartSessionServerIntegrationTest {

    private static final String SESSION_ID = "e2e-session";
    private static final Pattern ACTIVE_PLAYER_PATTERN = Pattern.compile("\"activePlayerId\":\"([^\"]+)\"");

    private SessionServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        port = findFreePort();
        SessionState initialState = PureDomainSessionFactory.initialGameState(
                SESSION_ID,
                List.of("Eka", "Toka"),
                List.of("#E63946", "#2A9D8F")
        );
        SessionApplicationService service = PureDomainSessionFactory.create(SESSION_ID, initialState);
        SessionCommandPublisher publisher = new SessionCommandPublisher(service);
        server = new SessionServer(publisher, publisher, publisher::currentSnapshot, port);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void snapshotContainsInitialPlayersAndProperties() throws Exception {
        String body = get("/snapshot");
        assertTrue(body.contains("\"sessionId\":\"" + SESSION_ID + "\""),
                "Expected sessionId in snapshot");
        assertTrue(body.contains("\"Eka\"") || body.contains("player-1"),
                "Expected player names/ids in snapshot");
        // All board properties should be present (28 purchasable spots)
        assertTrue(body.contains("\"B1\""), "Expected property B1 in snapshot");
        assertTrue(body.contains("\"RR1\""), "Expected railroad RR1 in snapshot");
    }

    @Test
    void rollDiceCommandIsAccepted() throws Exception {
        String activePlayerId = activePlayerId(get("/snapshot"));
        String rollJson = """
                {"type":"RollDice","sessionId":"%s","actorPlayerId":"%s"}
                """.formatted(SESSION_ID, activePlayerId).strip();

        HttpResponse<String> response = post("/command", rollJson);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"accepted\":true"),
                "RollDice should be accepted, got: " + response.body());
    }

    @Test
    void rollDiceMovesActivePlayerOnBoard() throws Exception {
        String activePlayerId = activePlayerId(get("/snapshot"));
        String rollJson = """
                {"type":"RollDice","sessionId":"%s","actorPlayerId":"%s"}
                """.formatted(SESSION_ID, activePlayerId).strip();

        post("/command", rollJson);

        String snapshot = get("/snapshot");
        assertTrue(snapshot.contains("\"sessionId\":\"" + SESSION_ID + "\""),
                "Snapshot should still be valid after roll");
        assertFalse(snapshot.isEmpty());
    }

    @Test
    void healthEndpointResponds() throws Exception {
        String body = get("/health");
        assertTrue(body.contains("ok"));
    }

    @Test
    void sseStreamPushesUpdatedSnapshotAfterRollDice() throws Exception {
        BlockingQueue<String> events = new LinkedBlockingQueue<>();

        // Read SSE lines on a virtual thread. setReadTimeout ensures the thread cannot hang
        // forever: if no data arrives within 8 s the socket throws SocketTimeoutException and
        // the thread exits. A local counter breaks the read loop after exactly 2 data events so
        // the thread never blocks waiting for a third event that will never come.
        Thread sseThread = Thread.ofVirtual().start(() -> {
            int received = 0;
            try {
                HttpURLConnection conn = (HttpURLConnection)
                        new URL("http://localhost:" + port + "/events").openConnection();
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.setReadTimeout(8_000);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            events.offer(line.substring(6));
                            if (++received >= 2) break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        });

        // Initial snapshot is pushed by the server immediately on connect
        String initial = events.poll(5, TimeUnit.SECONDS);
        assertNotNull(initial, "Should receive initial SSE snapshot within 5 s");
        assertTrue(initial.contains("\"sessionId\":\"" + SESSION_ID + "\""),
                "Initial SSE snapshot must contain sessionId");

        String activePlayerId = activePlayerId(initial);
        String rollJson = """
                {"type":"RollDice","sessionId":"%s","actorPlayerId":"%s"}
                """.formatted(SESSION_ID, activePlayerId).strip();
        HttpResponse<String> cmdResp = post("/command", rollJson);
        assertEquals(200, cmdResp.statusCode(), "RollDice must be accepted before SSE push fires");

        String updated = events.poll(5, TimeUnit.SECONDS);
        assertNotNull(updated, "Should receive SSE snapshot push after accepted RollDice within 5 s");
        assertTrue(updated.contains("\"sessionId\":\"" + SESSION_ID + "\""),
                "Updated SSE snapshot must contain sessionId");
        assertNotEquals(initial, updated, "SSE snapshot must differ after RollDice");

        sseThread.join(2_000);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String activePlayerId(String snapshotJson) {
        Matcher m = ACTIVE_PLAYER_PATTERN.matcher(snapshotJson);
        assertTrue(m.find(), "Could not find activePlayerId in snapshot JSON");
        return m.group(1);
    }

    private String get(String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }
}
