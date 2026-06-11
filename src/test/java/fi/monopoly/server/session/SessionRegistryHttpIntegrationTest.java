package fi.monopoly.server.session;

import fi.monopoly.application.command.SessionCommand;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.client.session.ClientSessionListener;
import fi.monopoly.client.session.ClientSessionUpdates;
import fi.monopoly.client.session.SessionCommandPort;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.server.transport.SessionHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for multi-session HTTP endpoints.
 *
 * <p>Tests {@code POST /sessions}, {@code GET /sessions}, session-scoped
 * {@code /command}, {@code /snapshot}, and {@code /events} endpoints.
 * Also verifies CORS headers and OPTIONS preflight responses.</p>
 */
class SessionRegistryHttpIntegrationTest {

    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("\"sessionId\":\"([^\"]+)\"");
    private static final Pattern ACTIVE_PLAYER_PATTERN = Pattern.compile("\"activePlayerId\":\"([^\"]+)\"");
    private static final Pattern PLAYER_ID_PATTERN = Pattern.compile("\"playerId\":\"([^\"]+)\"");
    private static final Pattern PLAYER_TOKEN_PATTERN = Pattern.compile("\"playerToken\":\"([^\"]+)\"");
    private static final Pattern HOST_TOKEN_PATTERN = Pattern.compile("\"hostToken\":\"([^\"]+)\"");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private int port;
    private SessionHttpServer httpServer;
    private SessionRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        port = findFreePort();
        registry = new SessionRegistry();

        SessionCommandPort noopCommand = new SessionCommandPort() {
            @Override public CommandResult handle(SessionCommand command) { throw new UnsupportedOperationException(); }
            @Override public SessionState currentState() { throw new UnsupportedOperationException(); }
        };
        ClientSessionUpdates noopUpdates = new ClientSessionUpdates() {
            @Override public void addListener(ClientSessionListener l) {}
            @Override public void removeListener(ClientSessionListener l) {}
        };

        httpServer = new SessionHttpServer(
                noopCommand, noopUpdates, () -> { throw new UnsupportedOperationException(); },
                port, registry);
        httpServer.start();
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) httpServer.stop();
    }

    // -------------------------------------------------------------------------
    // POST /sessions
    // -------------------------------------------------------------------------

    @Test
    void createSessionReturns201WithSessionId() throws Exception {
        HttpResponse<String> response = post("/sessions",
                "{\"names\":[\"Eka\",\"Toka\"],\"colors\":[\"#E63946\",\"#2A9D8F\"]}");
        assertEquals(201, response.statusCode());
        assertTrue(response.body().contains("\"sessionId\""), "Body should contain sessionId");
    }

    @Test
    void createSessionWithMissingNamesReturns400() throws Exception {
        HttpResponse<String> response = post("/sessions", "{\"colors\":[\"#E63946\"]}");
        assertEquals(400, response.statusCode());
    }

    @Test
    void createSessionWithDuplicateNamesReturns400() throws Exception {
        HttpResponse<String> response = post("/sessions",
                "{\"names\":[\"Eka\",\"Eka\"],\"colors\":[\"#E63946\",\"#2A9D8F\"]}");
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("DUPLICATE_PLAYER_NAME"),
                "Error body should contain DUPLICATE_PLAYER_NAME, got: " + response.body());
    }

    @Test
    void createSessionWithDuplicateNamesCaseInsensitiveReturns400() throws Exception {
        HttpResponse<String> response = post("/sessions",
                "{\"names\":[\"alice\",\"Alice\"],\"colors\":[\"#E63946\",\"#2A9D8F\"]}");
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("DUPLICATE_PLAYER_NAME"),
                "Error body should contain DUPLICATE_PLAYER_NAME, got: " + response.body());
    }

    @Test
    void createSessionWithDuplicateColorsReturns400() throws Exception {
        HttpResponse<String> response = post("/sessions",
                "{\"names\":[\"Eka\",\"Toka\"],\"colors\":[\"#E63946\",\"#E63946\"]}");
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("DUPLICATE_PLAYER_COLOR"),
                "Error body should contain DUPLICATE_PLAYER_COLOR, got: " + response.body());
    }

    @Test
    void createSessionWithDuplicateColorsCaseInsensitiveReturns400() throws Exception {
        HttpResponse<String> response = post("/sessions",
                "{\"names\":[\"Eka\",\"Toka\"],\"colors\":[\"#e63946\",\"#E63946\"]}");
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("DUPLICATE_PLAYER_COLOR"),
                "Error body should contain DUPLICATE_PLAYER_COLOR, got: " + response.body());
    }

    // -------------------------------------------------------------------------
    // GET /sessions
    // -------------------------------------------------------------------------

    @Test
    void listSessionsInitiallyEmpty() throws Exception {
        HttpResponse<String> response = get("/sessions");
        assertEquals(200, response.statusCode());
        assertEquals("[]", response.body().strip());
    }

    @Test
    void listSessionsContainsCreatedSession() throws Exception {
        post("/sessions", "{\"names\":[\"Alice\",\"Bob\"]}");
        HttpResponse<String> response = get("/sessions");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"sessionId\""));
        assertTrue(response.body().contains("Alice") || response.body().contains("Bob"));
    }

    // -------------------------------------------------------------------------
    // Session-scoped endpoints
    // -------------------------------------------------------------------------

    @Test
    void snapshotForCreatedSession() throws Exception {
        String sessionId = extractSessionId(post("/sessions",
                "{\"names\":[\"Eka\",\"Toka\"],\"colors\":[\"#E63946\",\"#2A9D8F\"]}").body());

        HttpResponse<String> snap = get("/sessions/" + sessionId + "/snapshot");
        assertEquals(200, snap.statusCode());
        assertTrue(snap.body().contains("\"sessionId\":\"" + sessionId + "\""));
    }

    @Test
    void rollDiceCommandAcceptedForCreatedSession() throws Exception {
        String sessionId = extractSessionId(post("/sessions",
                "{\"names\":[\"Eka\",\"Toka\"],\"colors\":[\"#E63946\",\"#2A9D8F\"]}").body());

        String snapshot = get("/sessions/" + sessionId + "/snapshot").body();
        String activePlayerId = extractActivePlayerId(snapshot);
        String rollJson = "{\"type\":\"RollDice\",\"sessionId\":\"%s\",\"actorPlayerId\":\"%s\"}"
                .formatted(sessionId, activePlayerId);

        HttpResponse<String> cmd = post("/sessions/" + sessionId + "/command", rollJson);
        assertEquals(200, cmd.statusCode());
        assertTrue(cmd.body().contains("\"accepted\":true"));
    }

    @Test
    void toggleMortgageCommandRejectedForUnownedProperty() throws Exception {
        String sessionId = extractSessionId(post("/sessions",
                "{\"names\":[\"Eka\",\"Toka\"],\"colors\":[\"#E63946\",\"#2A9D8F\"]}").body());

        String snapshot = get("/sessions/" + sessionId + "/snapshot").body();
        String activePlayerId = extractActivePlayerId(snapshot);
        String cmdJson = "{\"type\":\"ToggleMortgage\",\"sessionId\":\"%s\",\"actorPlayerId\":\"%s\",\"propertyId\":\"B1\"}"
                .formatted(sessionId, activePlayerId);

        HttpResponse<String> cmd = post("/sessions/" + sessionId + "/command", cmdJson);
        // Command must be deserialized correctly (no 400 or 500)
        assertNotEquals(400, cmd.statusCode(), "ToggleMortgage JSON should be deserializable");
        assertNotEquals(500, cmd.statusCode(), "ToggleMortgage should not cause a server error");
        assertTrue(cmd.body().contains("\"accepted\""), "Response should contain accepted field");
        assertTrue(cmd.body().contains("\"accepted\":false"), "Unowned property mortgage should be rejected");
    }

    @Test
    void buyBuildingRoundCommandRejectedForUnownedProperty() throws Exception {
        String sessionId = extractSessionId(post("/sessions",
                "{\"names\":[\"Eka\",\"Toka\"],\"colors\":[\"#E63946\",\"#2A9D8F\"]}").body());

        String snapshot = get("/sessions/" + sessionId + "/snapshot").body();
        String activePlayerId = extractActivePlayerId(snapshot);
        String cmdJson = "{\"type\":\"BuyBuildingRound\",\"sessionId\":\"%s\",\"actorPlayerId\":\"%s\",\"propertyId\":\"B1\"}"
                .formatted(sessionId, activePlayerId);

        HttpResponse<String> cmd = post("/sessions/" + sessionId + "/command", cmdJson);
        assertNotEquals(400, cmd.statusCode(), "BuyBuildingRound JSON should be deserializable");
        assertNotEquals(500, cmd.statusCode(), "BuyBuildingRound should not cause a server error");
        assertTrue(cmd.body().contains("\"accepted\""), "Response should contain accepted field");
        assertTrue(cmd.body().contains("\"accepted\":false"), "Building on unowned property should be rejected");
    }

    @Test
    void unknownCommandTypeReturns400() throws Exception {
        String sessionId = extractSessionId(post("/sessions",
                "{\"names\":[\"Eka\",\"Toka\"]}").body());
        HttpResponse<String> cmd = post("/sessions/" + sessionId + "/command",
                "{\"type\":\"NonExistentCommand\",\"sessionId\":\"" + sessionId + "\"}");
        assertEquals(400, cmd.statusCode());
    }

    @Test
    void snapshotForUnknownSessionReturns404() throws Exception {
        HttpResponse<String> response = get("/sessions/nonexistent-id/snapshot");
        assertEquals(404, response.statusCode());
    }

    @Test
    void commandForUnknownSessionReturns404() throws Exception {
        HttpResponse<String> response = post("/sessions/nonexistent-id/command",
                "{\"type\":\"RollDice\",\"sessionId\":\"x\",\"actorPlayerId\":\"p1\"}");
        assertEquals(404, response.statusCode());
    }

    @Test
    @DisabledIfSystemProperty(named = "skipSseTests", matches = "true",
            disabledReason = "SSE test is flaky under Docker CPU load — run with -DskipSseTests=false to enable")
    void sseStreamForCreatedSessionPushesInitialSnapshot() throws Exception {
        String sessionId = extractSessionId(post("/sessions",
                "{\"names\":[\"Eka\",\"Toka\"],\"colors\":[\"#E63946\",\"#2A9D8F\"]}").body());

        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        Thread sseThread = Thread.ofVirtual().start(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                        new URL("http://localhost:" + port + "/sessions/" + sessionId + "/events").openConnection();
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.setReadTimeout(15_000);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            events.offer(line.substring(6));
                            break; // only need the initial event
                        }
                    }
                }
            } catch (Exception ignored) {}
        });

        String initial = events.poll(15, TimeUnit.SECONDS);
        assertNotNull(initial, "Should receive initial SSE snapshot within 10 s");
        assertTrue(initial.contains("\"sessionId\":\"" + sessionId + "\""));

        sseThread.join(2_000);
    }

    // -------------------------------------------------------------------------
    // Player token validation
    // -------------------------------------------------------------------------

    /**
     * A command submitted with a wrong playerToken must be rejected with HTTP 403.
     * A command submitted with the correct playerToken must not return 403
     * (it may still be rejected with 422 for game-rule reasons if it is not the player's turn).
     */
    @Test
    void commandWithWrongPlayerToken_returns403() throws Exception {
        // 1. Create a lobby session
        HttpResponse<String> createResp = post("/sessions",
                "{\"lobbyMode\":true,\"hostName\":\"Alice\",\"hostColor\":\"#E63946\"}");
        assertEquals(201, createResp.statusCode());
        String sessionId    = extractSessionId(createResp.body());
        String hostToken    = extractPattern(HOST_TOKEN_PATTERN, createResp.body());
        String hostPlayerId = extractPattern(PLAYER_ID_PATTERN,  createResp.body());
        String validToken   = extractPattern(PLAYER_TOKEN_PATTERN, createResp.body());

        // 2. Add a bot so we have >= 2 players, then mark the host ready to auto-start the game
        post("/sessions/" + sessionId + "/lobby/bots", "{\"hostToken\":\"" + hostToken + "\"}");
        post("/sessions/" + sessionId + "/lobby/ready",
                "{\"playerId\":\"" + hostPlayerId + "\",\"playerToken\":\"" + validToken + "\",\"ready\":true}");

        // Wait briefly for the game to start (all-ready triggers auto-start synchronously in test)
        Thread.sleep(200);

        // 3. RollDice with a WRONG token — must be 403
        String wrongTokenCmd = "{\"type\":\"RollDice\",\"sessionId\":\"" + sessionId
                + "\",\"actorPlayerId\":\"" + hostPlayerId + "\",\"playerToken\":\"wrong-token\"}";
        HttpResponse<String> wrongResp = post("/sessions/" + sessionId + "/command", wrongTokenCmd);
        assertEquals(403, wrongResp.statusCode(),
                "Command with wrong playerToken should return 403, got: " + wrongResp.body());

        // 4. Same command for the HOST player with the CORRECT token — must NOT return 403.
        // The game may reject the command for a game-rule reason (422) if it is not the host's
        // turn, but it must never return 403 when the token matches.
        String correctTokenCmd = "{\"type\":\"RollDice\",\"sessionId\":\"" + sessionId
                + "\",\"actorPlayerId\":\"" + hostPlayerId + "\",\"playerToken\":\"" + validToken + "\"}";
        HttpResponse<String> correctResp = post("/sessions/" + sessionId + "/command", correctTokenCmd);
        // 200 = accepted (host's turn), 422 = game-rule rejection (bot's turn) — both are valid; 403 is not
        assertNotEquals(403, correctResp.statusCode(),
                "Command with correct playerToken must not return 403, got: " + correctResp.body());
    }

    // -------------------------------------------------------------------------
    // Debug state endpoint — no-auth current behavior
    // -------------------------------------------------------------------------

    /**
     * Documents the CURRENT behavior of {@code PUT /sessions/{id}/debug/state}:
     * any caller with a valid session id can apply a state patch — no authentication
     * is required. This test makes the design gap visible in the test suite.
     *
     * <ul>
     *   <li>Valid session + minimal patch → HTTP 200, {@code applied:true}</li>
     *   <li>Invalid session id → HTTP 404</li>
     * </ul>
     */
    @Test
    void debugStateEndpoint_requiresNoAuth_currentBehavior() throws Exception {
        // Create a session
        String sessionId = extractSessionId(post("/sessions",
                "{\"names\":[\"Eka\",\"Toka\"],\"colors\":[\"#E63946\",\"#2A9D8F\"]}").body());

        // GET snapshot to find a real playerId to patch
        String snapshot = get("/sessions/" + sessionId + "/snapshot").body();
        String activePlayerId = extractActivePlayerId(snapshot);

        // Minimal valid patch: set active player's cash to 2000
        String patch = "{\"players\":[{\"playerId\":\"" + activePlayerId + "\",\"cash\":2000}]}";
        HttpResponse<String> patchResp = put("/sessions/" + sessionId + "/debug/state", patch);
        assertEquals(200, patchResp.statusCode(),
                "Debug state patch should return 200 (no auth check), got: " + patchResp.body());
        assertTrue(patchResp.body().contains("\"applied\":true"),
                "Response should contain applied:true, got: " + patchResp.body());

        // Verify the patch was applied by checking the snapshot.
        // The snapshot has shape: { "state": { "players": [...] } }
        String updatedSnapshot = get("/sessions/" + sessionId + "/snapshot").body();
        JsonNode snapshotRoot = objectMapper.readTree(updatedSnapshot);
        JsonNode players = snapshotRoot.path("state").path("players");
        boolean cashUpdated = false;
        for (JsonNode player : players) {
            if (activePlayerId.equals(player.path("playerId").asText())) {
                cashUpdated = player.path("cash").asInt() == 2000;
                break;
            }
        }
        assertTrue(cashUpdated, "Player cash should have been updated to 2000 by debug patch");

        // Invalid sessionId must return 404
        HttpResponse<String> notFoundResp = put("/sessions/nonexistent-id/debug/state", patch);
        assertEquals(404, notFoundResp.statusCode(),
                "Debug state patch for unknown session should return 404");
    }

    // -------------------------------------------------------------------------
    // CORS
    // -------------------------------------------------------------------------

    @Test
    void corsHeadersPresentOnSnapshot() throws Exception {
        String sessionId = extractSessionId(post("/sessions",
                "{\"names\":[\"Eka\",\"Toka\"]}").body());
        HttpResponse<String> resp = get("/sessions/" + sessionId + "/snapshot");
        assertEquals("*", resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    }

    @Test
    void optionsPreflightReturns204() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/sessions"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, resp.statusCode());
        assertEquals("*", resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String extractSessionId(String json) {
        Matcher m = SESSION_ID_PATTERN.matcher(json);
        assertTrue(m.find(), "No sessionId in: " + json);
        return m.group(1);
    }

    private String extractActivePlayerId(String json) {
        Matcher m = ACTIVE_PLAYER_PATTERN.matcher(json);
        assertTrue(m.find(), "No activePlayerId in: " + json);
        return m.group(1);
    }

    private String extractPattern(Pattern pattern, String json) {
        Matcher m = pattern.matcher(json);
        assertTrue(m.find(), "Pattern " + pattern + " not found in: " + json);
        return m.group(1);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }
}
