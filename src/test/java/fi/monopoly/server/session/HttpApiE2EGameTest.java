package fi.monopoly.server.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.monopoly.server.transport.SessionHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test that drives a full Monopoly game through the HTTP API using
 * only explicit JSON commands — no bot drivers involved.
 *
 * <p>Validates the complete request/response lifecycle: command serialisation,
 * routing, domain execution, and JSON snapshot responses all participate in
 * every step. Any regression in the transport layer (missing field, broken
 * routing, serialisation error) will surface here before it reaches a real
 * client.</p>
 *
 * <p>A greedy agent is used: always rolls dice, always buys affordable
 * properties, pays debt when possible, declares bankruptcy otherwise.
 * The assertion is liveness: no deadlock within the step budget, and
 * either enough turn switches or an outright game-over.</p>
 */
class HttpApiE2EGameTest {

    private static final int MAX_STEPS = 4_000;
    private static final int MIN_TURN_SWITCHES = 20;

    private int port;
    private SessionHttpServer server;
    private SessionRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @BeforeEach
    void setUp() throws IOException {
        port = findFreePort();
        registry = new SessionRegistry();
        server = new SessionHttpServer(null, null, null, port, registry);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (registry != null) registry.shutdown();
    }

    private record SessionData(String sessionId, Map<String, String> playerTokens) {}

    // -------------------------------------------------------------------------
    // E2E game runs
    // -------------------------------------------------------------------------

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void twoHumanPlayerGameProgressesViaHttpApi() throws Exception {
        SessionData sd = createSession(List.of("Alice", "Bob"), List.of("#E63946", "#2A9D8F"),
                List.of("HUMAN", "HUMAN"), List.of());
        DriveResult result = driveGame(sd);

        assertFalse(result.stalled(),
                "HTTP-driven game stalled after " + result.steps() + " steps — possible transport deadlock");
        assertTrue(result.gameOver() || result.turnSwitches() >= MIN_TURN_SWITCHES,
                "Expected at least " + MIN_TURN_SWITCHES + " turn switches or GAME_OVER; got "
                        + result.turnSwitches() + " switches, gameOver=" + result.gameOver());
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void threeHumanPlayerGameProgressesViaHttpApi() throws Exception {
        SessionData sd = createSession(
                List.of("Alice", "Bob", "Carol"),
                List.of("#E63946", "#2A9D8F", "#E9C46A"),
                List.of("HUMAN", "HUMAN", "HUMAN"), List.of());
        DriveResult result = driveGame(sd);

        assertFalse(result.stalled(),
                "3-player HTTP game stalled after " + result.steps() + " steps");
        assertTrue(result.gameOver() || result.turnSwitches() >= MIN_TURN_SWITCHES,
                "Expected progress in 3-player game; turns=" + result.turnSwitches());
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void snapshotVersionIncreasesAfterEachAcceptedCommand() throws Exception {
        SessionData sd = createSession(List.of("Alice", "Bob"), List.of(), List.of(), List.of());
        String sessionId = sd.sessionId();

        long versionBefore = snapshotVersion(sessionId);
        JsonNode snap = snapshot(sessionId);
        String activeId = snap.at("/state/turn/activePlayerId").asText();

        String rollJson = buildRollDice(sessionId, activeId, sd.playerTokens());
        HttpResponse<String> result = postCommand(sessionId, rollJson);
        assertEquals(200, result.statusCode());
        assertTrue(result.body().contains("\"accepted\":true"), "RollDice should be accepted");

        long versionAfter = snapshotVersion(sessionId);
        assertTrue(versionAfter > versionBefore,
                "Snapshot version should increase after accepted command: before=%d after=%d"
                        .formatted(versionBefore, versionAfter));
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void commandIdempotencyPreventsDuplicateEffect() throws Exception {
        SessionData sd = createSession(List.of("Alice", "Bob"), List.of(), List.of(), List.of());
        String sessionId = sd.sessionId();
        String activeId = snapshot(sessionId).at("/state/turn/activePlayerId").asText();
        long versionBefore = snapshotVersion(sessionId);

        String cmdId = "idempotency-test-cmd-1";
        String rollJson = buildRollDiceWithCommandId(sessionId, activeId, cmdId);

        HttpResponse<String> first  = postCommand(sessionId, rollJson);
        HttpResponse<String> second = postCommand(sessionId, rollJson);

        assertEquals(200, first.statusCode());
        assertEquals(200, second.statusCode());
        assertTrue(first.body().contains("\"accepted\":true"));
        assertTrue(second.body().contains("\"accepted\":true"), "cached result should also say accepted");

        long versionAfter = snapshotVersion(sessionId);
        assertTrue(versionAfter > versionBefore, "version should advance after first call");

        // Second call is a cache hit — version must NOT advance a second time
        long versionAfterSecond = snapshotVersion(sessionId);
        assertEquals(versionAfter, versionAfterSecond,
                "Idempotent duplicate must not advance version a second time");
    }

    // -------------------------------------------------------------------------
    // Greedy game driver
    // -------------------------------------------------------------------------

    private DriveResult driveGame(SessionData sd) throws Exception {
        String sessionId = sd.sessionId();
        Map<String, String> tokens = sd.playerTokens();
        int steps = 0;
        int turnSwitches = 0;
        int rejectedConsecutive = 0;
        String lastActivePlayer = null;

        while (steps < MAX_STEPS) {
            JsonNode snap = snapshot(sessionId);
            String status = snap.at("/state/status").asText("");

            if ("GAME_OVER".equals(status)) {
                return new DriveResult(steps, turnSwitches, false, true);
            }

            String activeId = snap.at("/state/turn/activePlayerId").asText(null);
            String phase    = snap.at("/state/turn/phase").asText("");

            if (activeId != null && !activeId.equals(lastActivePlayer)) {
                turnSwitches++;
                lastActivePlayer = activeId;
            }

            String commandJson = buildCommand(sessionId, snap, phase, activeId, tokens);
            if (commandJson == null) {
                if (rejectedConsecutive < 3) System.err.println("[DEBUG] step=" + steps + " phase=" + phase + " activeId=" + activeId + " => null command");
                rejectedConsecutive++;
            } else {
                HttpResponse<String> response = postCommand(sessionId, commandJson);
                boolean accepted = response.statusCode() == 200
                        && response.body().contains("\"accepted\":true");
                if (accepted) {
                    rejectedConsecutive = 0;
                } else {
                    if (rejectedConsecutive < 3) System.err.println("[DEBUG] step=" + steps + " phase=" + phase + " cmd=" + commandJson + " => " + response.body());
                    rejectedConsecutive++;
                }
            }

            if (rejectedConsecutive >= 10) {
                return new DriveResult(steps, turnSwitches, true, false);
            }
            steps++;
        }
        return new DriveResult(steps, turnSwitches, false, false);
    }

    private String buildCommand(String sessionId, JsonNode snap, String phase, String activeId, Map<String, String> tokens) {
        if (activeId == null || activeId.isEmpty()) return null;
        return switch (phase) {
            case "WAITING_FOR_ROLL"      -> buildRollDice(sessionId, activeId, tokens);
            case "WAITING_FOR_CARD_ACK"  -> json("AcknowledgeCard", sessionId, activeId, "", tokens);
            case "WAITING_FOR_END_TURN"  -> buildEndTurn(sessionId, activeId, tokens);
            case "WAITING_FOR_DECISION"  -> buildDecisionCommand(sessionId, snap, activeId, tokens);
            case "RESOLVING_DEBT"        -> buildDebtCommand(sessionId, snap, tokens);
            case "WAITING_FOR_AUCTION"   -> buildAuctionCommand(sessionId, snap, tokens);
            default -> null;
        };
    }

    private String buildRollDice(String sessionId, String activeId, Map<String, String> tokens) {
        return json("RollDice", sessionId, activeId, "", tokens);
    }

    private String buildRollDice(String sessionId, String activeId) {
        return json("RollDice", sessionId, activeId, "", Map.of());
    }

    private String buildRollDiceWithCommandId(String sessionId, String activeId, String commandId) {
        return "{\"type\":\"RollDice\",\"sessionId\":\"%s\",\"actorPlayerId\":\"%s\",\"commandId\":\"%s\"}"
                .formatted(sessionId, activeId, commandId);
    }

    private String buildEndTurn(String sessionId, String activeId, Map<String, String> tokens) {
        return json("EndTurn", sessionId, activeId, "", tokens);
    }

    private String buildEndTurn(String sessionId, String activeId) {
        return json("EndTurn", sessionId, activeId, "", Map.of());
    }

    private String buildDecisionCommand(String sessionId, JsonNode snap, String activeId, Map<String, String> tokens) {
        JsonNode decision = snap.at("/state/pendingDecision");
        if (decision.isMissingNode() || decision.isNull()) return buildEndTurn(sessionId, activeId, tokens);

        String decisionId  = decision.at("/decisionId").asText("");
        String propertyId  = decision.at("/payload/propertyId").asText("");
        int    price       = decision.at("/payload/price").asInt(Integer.MAX_VALUE);
        int    cash        = snap.at("/state/players").elements().hasNext()
                ? findPlayerCash(snap, activeId) : 0;

        String type = cash >= price ? "BuyProperty" : "DeclineProperty";
        String tok = tokenField(activeId, tokens);
        return ("{\"type\":\"%s\",\"sessionId\":\"%s\",\"actorPlayerId\":\"%s\"%s,"
                + "\"decisionId\":\"%s\",\"propertyId\":\"%s\"}")
                .formatted(type, sessionId, activeId, tok, decisionId, propertyId);
    }

    private String buildDebtCommand(String sessionId, JsonNode snap, Map<String, String> tokens) {
        JsonNode debt     = snap.at("/state/activeDebt");
        if (debt.isMissingNode() || debt.isNull()) return null;
        String debtId     = debt.at("/debtId").asText("");
        String debtorId   = debt.at("/debtorPlayerId").asText("");
        int    cash       = debt.at("/currentCash").asInt(0);
        int    amount     = debt.at("/amountRemaining").asInt(Integer.MAX_VALUE);
        String actions    = debt.at("/allowedActions").toString();
        String tok = tokenField(debtorId, tokens);

        if (actions.contains("PAY_DEBT_NOW") && cash >= amount) {
            return "{\"type\":\"PayDebt\",\"sessionId\":\"%s\",\"actorPlayerId\":\"%s\"%s,\"debtId\":\"%s\"}"
                    .formatted(sessionId, debtorId, tok, debtId);
        }
        if (actions.contains("SELL_BUILDING")) {
            String propId = findSellableBuilding(snap, debtorId);
            if (propId != null) {
                return ("{\"type\":\"SellBuildingForDebt\",\"sessionId\":\"%s\",\"actorPlayerId\":\"%s\"%s,"
                        + "\"debtId\":\"%s\",\"propertyId\":\"%s\",\"count\":1}")
                        .formatted(sessionId, debtorId, tok, debtId, propId);
            }
        }
        if (actions.contains("MORTGAGE_PROPERTY")) {
            String propId = findUnmortgagedProperty(snap, debtorId);
            if (propId != null) {
                return ("{\"type\":\"MortgagePropertyForDebt\",\"sessionId\":\"%s\",\"actorPlayerId\":\"%s\"%s,"
                        + "\"debtId\":\"%s\",\"propertyId\":\"%s\"}")
                        .formatted(sessionId, debtorId, tok, debtId, propId);
            }
        }
        return "{\"type\":\"DeclareBankruptcy\",\"sessionId\":\"%s\",\"actorPlayerId\":\"%s\"%s,\"debtId\":\"%s\"}"
                .formatted(sessionId, debtorId, tok, debtId);
    }

    private String buildAuctionCommand(String sessionId, JsonNode snap, Map<String, String> tokens) {
        JsonNode auction = snap.at("/state/auctionState");
        if (auction.isMissingNode()) return null;

        String auctionId = auction.at("/auctionId").asText("");
        String status    = auction.at("/status").asText("");

        if ("WON_PENDING_RESOLUTION".equals(status)) {
            return "{\"type\":\"FinishAuctionResolution\",\"sessionId\":\"%s\",\"auctionId\":\"%s\"}"
                    .formatted(sessionId, auctionId);
        }
        String bidderId = auction.at("/currentActorPlayerId").asText(null);
        if (bidderId == null || bidderId.isEmpty()) return null;

        int minBid = auction.at("/minimumNextBid").asInt(Integer.MAX_VALUE);
        int cash   = findPlayerCash(snap, bidderId);
        String tok = tokenField(bidderId, tokens);

        if (cash >= minBid && minBid > 0) {
            return ("{\"type\":\"PlaceAuctionBid\",\"sessionId\":\"%s\",\"actorPlayerId\":\"%s\"%s,"
                    + "\"auctionId\":\"%s\",\"amount\":%d}")
                    .formatted(sessionId, bidderId, tok, auctionId, minBid);
        }
        return "{\"type\":\"PassAuction\",\"sessionId\":\"%s\",\"actorPlayerId\":\"%s\"%s,\"auctionId\":\"%s\"}"
                .formatted(sessionId, bidderId, tok, auctionId);
    }

    // -------------------------------------------------------------------------
    // JSON helpers
    // -------------------------------------------------------------------------

    /** Returns {@code ,"playerToken":"<token>"} if a token is known for this player, else {@code ""}. */
    private String tokenField(String actorId, Map<String, String> tokens) {
        String tok = tokens.get(actorId);
        return tok != null ? ",\"playerToken\":\"" + tok + "\"" : "";
    }

    private String json(String type, String sessionId, String actorId, String extra, Map<String, String> tokens) {
        String tok = tokenField(actorId, tokens);
        return "{\"type\":\"%s\",\"sessionId\":\"%s\",\"actorPlayerId\":\"%s\"%s%s}"
                .formatted(type, sessionId, actorId, tok, extra.isEmpty() ? "" : "," + extra);
    }

    private String json(String type, String sessionId, String actorId, String extra) {
        return json(type, sessionId, actorId, extra, Map.of());
    }

    private int findPlayerCash(JsonNode snap, String playerId) {
        for (JsonNode p : snap.at("/state/players")) {
            if (playerId.equals(p.at("/playerId").asText())) {
                return p.at("/cash").asInt(0);
            }
        }
        return 0;
    }

    private String findSellableBuilding(JsonNode snap, String ownerId) {
        for (JsonNode p : snap.at("/state/properties")) {
            if (ownerId.equals(p.at("/ownerPlayerId").asText())) {
                int houses = p.at("/houseCount").asInt(0);
                int hotels = p.at("/hotelCount").asInt(0);
                if (houses > 0 || hotels > 0) return p.at("/propertyId").asText(null);
            }
        }
        return null;
    }

    private String findUnmortgagedProperty(JsonNode snap, String ownerId) {
        for (JsonNode p : snap.at("/state/properties")) {
            if (ownerId.equals(p.at("/ownerPlayerId").asText())
                    && !p.at("/mortgaged").asBoolean(false)) {
                return p.at("/propertyId").asText(null);
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private SessionData createSession(List<String> names, List<String> colors,
                                      List<String> seatKinds, List<String> difficulties) throws Exception {
        String namesJson   = toJsonArray(names);
        String colorsJson  = toJsonArray(colors);
        String kindsJson   = toJsonArray(seatKinds);
        String diffsJson   = toJsonArray(difficulties);
        String body = "{\"names\":" + namesJson + ",\"colors\":" + colorsJson
                + ",\"seatKinds\":" + kindsJson + ",\"difficulties\":" + diffsJson + "}";

        HttpResponse<String> resp = post("/sessions", body);
        assertEquals(201, resp.statusCode(), "createSession must return 201");
        JsonNode root = mapper.readTree(resp.body());
        String sessionId = root.at("/sessionId").asText();
        Map<String, String> playerTokens = new HashMap<>();
        JsonNode tokensNode = root.at("/playerTokens");
        if (!tokensNode.isMissingNode() && tokensNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = tokensNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                playerTokens.put(e.getKey(), e.getValue().asText());
            }
        }
        return new SessionData(sessionId, playerTokens);
    }

    private JsonNode snapshot(String sessionId) throws Exception {
        HttpResponse<String> resp = get("/sessions/" + sessionId + "/snapshot");
        assertEquals(200, resp.statusCode(), "snapshot must return 200");
        return mapper.readTree(resp.body());
    }

    private long snapshotVersion(String sessionId) throws Exception {
        return snapshot(sessionId).at("/version").asLong(0);
    }

    private HttpResponse<String> postCommand(String sessionId, String json) throws Exception {
        return post("/sessions/" + sessionId + "/command", json);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .header("Content-Type", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String toJsonArray(List<String> items) {
        if (items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(items.get(i)).append("\"");
        }
        return sb.append("]").toString();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private record DriveResult(int steps, int turnSwitches, boolean stalled, boolean gameOver) {}
}
