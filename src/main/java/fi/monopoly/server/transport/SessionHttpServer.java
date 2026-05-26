package fi.monopoly.server.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializationFeature;
import fi.monopoly.application.command.SessionCommand;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.client.session.ClientSessionListener;
import fi.monopoly.client.session.ClientSessionSnapshot;
import fi.monopoly.client.session.ClientSessionUpdates;
import fi.monopoly.client.session.SessionCommandPort;
import fi.monopoly.server.session.SessionCommandPublisher;
import fi.monopoly.domain.session.SeatKind;
import fi.monopoly.server.session.SessionRegistry;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.sse.SseClient;
import io.javalin.json.JavalinJackson;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * HTTP server exposing one or more game sessions via Javalin/Jetty.
 *
 * <h2>Single-session endpoints (backward-compatible)</h2>
 * <ul>
 *   <li>{@code POST /command} — submit a {@link SessionCommand} as JSON</li>
 *   <li>{@code GET  /snapshot} — current {@link ClientSessionSnapshot} (poll)</li>
 *   <li>{@code GET  /events} — SSE stream of snapshot updates</li>
 *   <li>{@code GET  /health} — liveness check</li>
 * </ul>
 *
 * <h2>Multi-session endpoints (active when a {@link SessionRegistry} is provided)</h2>
 * <ul>
 *   <li>{@code POST /sessions} — create a new session</li>
 *   <li>{@code GET  /sessions} — list all sessions</li>
 *   <li>{@code POST /sessions/{id}/command} — submit command to session</li>
 *   <li>{@code GET  /sessions/{id}/snapshot} — snapshot for session</li>
 *   <li>{@code GET  /sessions/{id}/events} — SSE stream for session</li>
 * </ul>
 */
@Slf4j
public final class SessionHttpServer {

    private final SessionCommandPort commandPort;
    private final ClientSessionUpdates sessionUpdates;
    private final Supplier<ClientSessionSnapshot> snapshotSupplier;
    private final int port;
    private final ObjectMapper objectMapper;
    private final SessionCommandMapper commandMapper;
    private final SessionRegistry registry;
    private final ObjectReader strictReader;

    private final long startTimeMs = System.currentTimeMillis();

    private Javalin app;

    /** Single-session constructor (backward-compatible). No {@code /sessions} endpoints. */
    public SessionHttpServer(
            SessionCommandPort commandPort,
            ClientSessionUpdates sessionUpdates,
            Supplier<ClientSessionSnapshot> snapshotSupplier,
            int port) {
        this(commandPort, sessionUpdates, snapshotSupplier, port, null);
    }

    /** Multi-session constructor. Activates {@code /sessions} endpoints. */
    public SessionHttpServer(
            SessionCommandPort commandPort,
            ClientSessionUpdates sessionUpdates,
            Supplier<ClientSessionSnapshot> snapshotSupplier,
            int port,
            SessionRegistry registry) {
        this.commandPort = commandPort;
        this.sessionUpdates = sessionUpdates;
        this.snapshotSupplier = snapshotSupplier;
        this.port = port;
        this.registry = registry;
        this.objectMapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        this.commandMapper = new SessionCommandMapper(objectMapper);
        this.strictReader = objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public void start() {
        app = Javalin.create(config -> {
            config.concurrency.useVirtualThreads = true;
            config.jsonMapper(new JavalinJackson(objectMapper, false));

            // CORS — unconditional, matching old behavior (browsers require Origin header anyway)
            config.routes.before(ctx -> {
                ctx.header("Access-Control-Allow-Origin", "*");
                ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                ctx.header("Access-Control-Allow-Headers", "Content-Type, Last-Event-ID");
            });
            config.routes.options("/*", ctx -> ctx.status(204));

            config.routes.get("/ping", ctx -> ctx.status(204));

            config.routes.get("/health", ctx -> {
                int activeSessions = registry != null ? registry.list().size() : -1;
                long uptimeSeconds = (System.currentTimeMillis() - startTimeMs) / 1000;
                ctx.json(Map.of(
                        "status", "ok",
                        "sessions", activeSessions,
                        "uptimeSeconds", uptimeSeconds,
                        "version", "1.0-SNAPSHOT"
                ));
            });
            config.routes.get("/openapi.yaml", ctx -> {
                ctx.contentType("text/yaml");
                var stream = getClass().getResourceAsStream("/openapi.yaml");
                if (stream != null) ctx.result(stream);
                else ctx.status(404).result("openapi.yaml not found");
            });
            config.routes.get("/metrics", ctx -> {
                int activeSessions = registry != null ? registry.list().size() : 0;
                ctx.contentType("text/plain; version=0.0.4; charset=utf-8")
                   .result(GlobalMetrics.prometheusText(activeSessions));
            });
            config.routes.get("/docs", ctx -> {
                ctx.contentType("text/html");
                ctx.result("""
                    <!DOCTYPE html>
                    <html>
                    <head>
                      <title>Monopoly API</title>
                      <meta charset="utf-8"/>
                      <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
                    </head>
                    <body>
                      <div id="swagger-ui"></div>
                      <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
                      <script>
                        SwaggerUIBundle({ url: '/openapi.yaml', dom_id: '#swagger-ui', tryItOutEnabled: true,
                                          layout: 'BaseLayout', deepLinking: true });
                      </script>
                    </body>
                    </html>
                    """);
            });

            // Single-session endpoints — only registered when single-session params are wired up
            if (commandPort != null && sessionUpdates != null && snapshotSupplier != null) {
                config.routes.post("/command", ctx -> handleCommandFor(ctx, commandPort));
                config.routes.get("/snapshot", ctx -> ctx.json(snapshotSupplier.get()));
                config.routes.sse("/events", client -> streamEvents(client, sessionUpdates, snapshotSupplier));
            }

            // Multi-session endpoints
            if (registry != null) {
                config.routes.get("/sessions", ctx -> ctx.json(registry.list()));
                config.routes.post("/sessions", this::handleSessionsCreate);
                config.routes.delete("/sessions/{id}", ctx -> {
                    String id = ctx.pathParam("id");
                    if (registry.get(id).isEmpty()) throw new NotFoundResponse("Session not found: " + id);
                    registry.remove(id);
                    ctx.status(204);
                });
                config.routes.post("/sessions/{id}/join", this::handleLobbyJoin);
                config.routes.post("/sessions/{id}/lobby/bots", this::handleLobbyAddBot);
                config.routes.delete("/sessions/{id}/lobby/bots/{seatId}", this::handleLobbyRemoveBot);
                config.routes.post("/sessions/{id}/lobby/ready", this::handleLobbyReady);
                config.routes.get("/sessions/{id}/settings", this::handleGetSettings);
                config.routes.put("/sessions/{id}/settings", this::handleSettings);
                config.routes.post("/sessions/{id}/command", ctx ->
                        handleCommandFor(ctx, requireSession(ctx)));
                config.routes.get("/sessions/{id}/snapshot", ctx ->
                        ctx.json(requireSession(ctx).currentSnapshot()));
                config.routes.sse("/sessions/{id}/events", client -> {
                    Optional<SessionCommandPublisher> pub = registry.get(client.ctx().pathParam("id"));
                    if (pub.isEmpty()) return;
                    streamEvents(client, pub.get(), pub.get()::currentSnapshot);
                });
            }
        }).start(port);
        log.info("Session HTTP server started on port {}", port);
    }

    public void stop() {
        if (app != null) {
            app.stop();
            log.info("Session HTTP server stopped");
        }
    }

    public int port() {
        return port;
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private void handleCommandFor(Context ctx, SessionCommandPort port) {
        try {
            byte[] body = ctx.bodyAsBytes();
            String pathSessionId = ctx.pathParamMap().containsKey("id") ? ctx.pathParam("id") : null;

            if (registry != null && pathSessionId != null) {
                JsonNode tokenNode = objectMapper.readTree(body);
                String commandType = tokenNode.path("type").asText();
                if ("AbortGame".equals(commandType)) {
                    String hostToken = tokenNode.path("hostToken").asText(null);
                    if (!registry.validateHostToken(pathSessionId, hostToken)) {
                        ctx.status(403).json(Map.of("error", "UNAUTHORIZED"));
                        return;
                    }
                } else {
                    String actorPlayerId = tokenNode.path("actorPlayerId").asText(null);
                    String playerToken = tokenNode.path("playerToken").asText(null);
                    if (actorPlayerId != null && !registry.validatePlayerToken(pathSessionId, actorPlayerId, playerToken)) {
                        ctx.status(403).json(Map.of("error", "UNAUTHORIZED"));
                        return;
                    }
                }
            }

            SessionCommand command = commandMapper.fromJson(body, pathSessionId);

            CommandResult result;
            if (port instanceof SessionCommandPublisher publisher) {
                String commandId = extractCommandId(body);
                result = publisher.handleIdempotent(command, commandId);
            } else {
                result = port.handle(command);
            }

            ctx.status(result.accepted() ? 200 : 422)
               .json(new CommandResultView(result.accepted(), result.rejections()));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error handling command", e);
            ctx.status(500).json(Map.of("error", "Internal server error"));
        }
    }

    private String extractCommandId(byte[] body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode cid = node.get("commandId");
            if (cid != null && !cid.isNull() && cid.isTextual()) return cid.asText();
        } catch (Exception ignored) {}
        return null;
    }

    private void handleSessionsCreate(Context ctx) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(ctx.bodyAsBytes(), Map.class);
            @SuppressWarnings("unchecked")
            List<String> names = (List<String>) request.get("names");
            @SuppressWarnings("unchecked")
            List<String> colors = request.get("colors") != null
                    ? (List<String>) request.get("colors") : List.of();
            Boolean lobbyMode = request.get("lobbyMode") instanceof Boolean b ? b : Boolean.FALSE;
            if (Boolean.TRUE.equals(lobbyMode)) {
                String hostName = request.get("hostName") instanceof String s ? s.trim() : "Pelaaja";
                String hostColor = request.get("hostColor") instanceof String s ? s : null;
                if (hostName.isEmpty()) hostName = "Pelaaja";
                var result = registry.createLobby(hostName, hostColor);
                ctx.status(201).json(Map.of(
                        "sessionId", result.sessionId(),
                        "hostToken", result.hostToken(),
                        "playerId", result.hostPlayerId(),
                        "playerToken", result.hostPlayerToken()));
                return;
            }
            if (names == null || names.isEmpty()) {
                ctx.status(400).json(Map.of("error", "names is required"));
                return;
            }
            @SuppressWarnings("unchecked")
            List<String> seatKindStrings = request.get("seatKinds") != null
                    ? (List<String>) request.get("seatKinds") : List.of();
            @SuppressWarnings("unchecked")
            List<String> difficultyStrings = request.get("difficulties") != null
                    ? (List<String>) request.get("difficulties") : List.of();

            List<fi.monopoly.domain.session.SeatKind> seatKinds = seatKindStrings.stream()
                    .map(s -> {
                        try { return fi.monopoly.domain.session.SeatKind.valueOf(s.toUpperCase()); }
                        catch (IllegalArgumentException e) { return fi.monopoly.domain.session.SeatKind.HUMAN; }
                    }).toList();
            List<fi.monopoly.domain.session.BotDifficulty> difficulties = difficultyStrings.stream()
                    .map(s -> {
                        try { return fi.monopoly.domain.session.BotDifficulty.valueOf(s.toUpperCase()); }
                        catch (IllegalArgumentException e) { return fi.monopoly.domain.session.BotDifficulty.STRONG; }
                    }).toList();

            var result = registry.create(names, colors, seatKinds, difficulties);
            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("sessionId", result.sessionId());
            if (result.hostToken() != null) resp.put("hostToken", result.hostToken());
            ctx.status(201).json(resp);
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating session", e);
            ctx.status(500).json(Map.of("error", "Internal server error"));
        }
    }

    private void handleLobbyJoin(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            if (registry.get(id).isEmpty()) throw new NotFoundResponse("Session not found: " + id);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(ctx.bodyAsBytes(), Map.class);
            String name = body.get("name") instanceof String s ? s.trim() : "";
            String color = body.get("color") instanceof String s ? s : null;
            if (name.isEmpty()) {
                ctx.status(400).json(Map.of("error", "name is required"));
                return;
            }
            registry.joinLobby(id, name, color)
                    .ifPresentOrElse(
                            r -> ctx.status(200).json(Map.of(
                                    "playerId", r.seat().playerId(),
                                    "seatId", r.seat().seatId(),
                                    "tokenColorHex", r.seat().tokenColorHex(),
                                    "playerToken", r.playerToken())),
                            () -> ctx.status(409).json(Map.of("error", "No available seats or session not in LOBBY state")));
        } catch (NotFoundResponse e) {
            throw e;
        } catch (Exception e) {
            log.error("Error joining lobby", e);
            ctx.status(500).json(Map.of("error", "Internal server error"));
        }
    }

    private void handleLobbyAddBot(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            if (registry.get(id).isEmpty()) throw new NotFoundResponse("Session not found: " + id);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(ctx.bodyAsBytes(), Map.class);
            String hostToken = body.get("hostToken") instanceof String s ? s : null;
            if (!registry.validateHostToken(id, hostToken)) {
                ctx.status(403).json(Map.of("error", "UNAUTHORIZED"));
                return;
            }
            registry.addLobbyBot(id)
                    .ifPresentOrElse(
                            seat -> ctx.status(200).json(Map.of("seatId", seat.seatId(), "name", seat.displayName())),
                            () -> ctx.status(409).json(Map.of("error", "Lobby is full or not in LOBBY state")));
        } catch (NotFoundResponse e) {
            throw e;
        } catch (Exception e) {
            log.error("Error adding lobby bot", e);
            ctx.status(500).json(Map.of("error", "Internal server error"));
        }
    }

    private void handleLobbyRemoveBot(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            String seatId = ctx.pathParam("seatId");
            if (registry.get(id).isEmpty()) throw new NotFoundResponse("Session not found: " + id);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(ctx.bodyAsBytes(), Map.class);
            String hostToken = body.get("hostToken") instanceof String s ? s : null;
            if (!registry.validateHostToken(id, hostToken)) {
                ctx.status(403).json(Map.of("error", "UNAUTHORIZED"));
                return;
            }
            boolean removed = registry.removeLobbyBot(id, seatId);
            if (removed) {
                ctx.status(200).json(Map.of("removed", true));
            } else {
                ctx.status(409).json(Map.of("error", "Bot seat not found or not in LOBBY state"));
            }
        } catch (NotFoundResponse e) {
            throw e;
        } catch (Exception e) {
            log.error("Error removing lobby bot", e);
            ctx.status(500).json(Map.of("error", "Internal server error"));
        }
    }

    private void handleLobbyReady(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            if (registry.get(id).isEmpty()) throw new NotFoundResponse("Session not found: " + id);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(ctx.bodyAsBytes(), Map.class);
            String playerId = body.get("playerId") instanceof String s ? s : null;
            String playerToken = body.get("playerToken") instanceof String s ? s : null;
            boolean ready = body.get("ready") instanceof Boolean b ? b : true;
            if (!registry.validatePlayerToken(id, playerId, playerToken)) {
                ctx.status(403).json(Map.of("error", "UNAUTHORIZED"));
                return;
            }
            boolean changed = registry.setPlayerReady(id, playerId, ready);
            ctx.status(200).json(Map.of("changed", changed));
        } catch (NotFoundResponse e) {
            throw e;
        } catch (Exception e) {
            log.error("Error setting lobby ready", e);
            ctx.status(500).json(Map.of("error", "Internal server error"));
        }
    }

    private void handleGetSettings(Context ctx) {
        String id = ctx.pathParam("id");
        if (registry.get(id).isEmpty()) throw new NotFoundResponse("Session not found: " + id);
        double mult = registry.getBotSpeedMultiplier(id);
        String speed = mult < 0 ? "n/a (no bots)" : mult <= 0.1 ? "fast" : mult >= 2.0 ? "slow" : "normal";
        ctx.json(Map.of("botSpeedMultiplier", mult < 0 ? "n/a" : String.valueOf(mult), "botSpeed", speed));
    }

    private enum BotSpeed {
        fast, normal, slow;
        double toMultiplier() {
            return switch (this) {
                case fast   -> 0.0;
                case slow   -> 2.5;
                case normal -> 1.0;
            };
        }
    }

    private record SettingsRequest(BotSpeed botSpeed) {}

    private void handleSettings(Context ctx) {
        String id = ctx.pathParam("id");
        if (registry.get(id).isEmpty()) throw new NotFoundResponse("Session not found: " + id);
        SettingsRequest req;
        try {
            req = strictReader.readValue(ctx.bodyAsBytes(), SettingsRequest.class);
        } catch (JsonProcessingException e) {
            throw new BadRequestResponse("Invalid settings body: " + e.getOriginalMessage());
        } catch (Exception e) {
            throw new BadRequestResponse("Could not parse request body");
        }
        if (req.botSpeed() != null) {
            double mult = req.botSpeed().toMultiplier();
            registry.setBotSpeed(id, mult);
            log.debug("Session {} botSpeed={} ({}x)", id.substring(0, 8), req.botSpeed(), mult);
        }
        ctx.status(200).json(Map.of("ok", true));
    }

    // -------------------------------------------------------------------------
    // SSE
    // -------------------------------------------------------------------------

    /**
     * Sets up an SSE connection: subscribes to session updates, sends the initial snapshot
     * (unless the client already has the current version via {@code Last-Event-ID}),
     * then calls {@link SseClient#keepAlive()} to hold the connection open asynchronously.
     * Future snapshots are pushed directly from the listener callback. The listener is removed
     * when the client disconnects (via the {@code onClose} hook).
     *
     * <p>Each SSE event carries the full {@link ClientSessionSnapshot} including its
     * {@code version} field. Clients that reconnect should pass the version they last received
     * as the {@code Last-Event-ID} header to avoid replaying an already-known snapshot.</p>
     */
    private void streamEvents(
            SseClient client,
            ClientSessionUpdates updates,
            Supplier<ClientSessionSnapshot> snapshotSupplier) {
        ClientSessionListener listener = snapshot -> {
            if (!client.terminated()) {
                try {
                    client.sendEvent(snapshot.stampedNow());
                } catch (Exception e) {
                    client.close();
                }
            }
        };
        updates.addListener(listener);
        client.onClose(() -> updates.removeListener(listener));
        client.keepAlive();
        // Deliver initial snapshot on a separate virtual thread so keepAlive()'s
        // ctx.future() is processed by the event loop before we write data.
        // The small sleep gives the event loop time to complete keepAlive() setup before
        // we call sendEvent — without it there is a race where sendEvent fires before
        // the async response is ready and the write is silently dropped.
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(30);
                ClientSessionSnapshot current = snapshotSupplier.get();
                long clientVersion = parseLastEventId(client.ctx().header("Last-Event-ID"));
                if (clientVersion < current.version()) {
                    client.sendEvent(current.stampedNow());
                }
            } catch (Exception ignored) {}
        });
    }

    private static long parseLastEventId(String header) {
        if (header == null || header.isBlank()) return -1L;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SessionCommandPublisher requireSession(Context ctx) {
        String id = ctx.pathParam("id");
        return registry.get(id)
                .orElseThrow(() -> new NotFoundResponse("Session not found: " + id));
    }

    private record CommandResultView(
            boolean accepted,
            List<fi.monopoly.application.result.CommandRejection> rejections) {}
}
