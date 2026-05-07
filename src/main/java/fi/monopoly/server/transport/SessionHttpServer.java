package fi.monopoly.server.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fi.monopoly.application.command.SessionCommand;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.client.session.ClientSessionListener;
import fi.monopoly.client.session.ClientSessionSnapshot;
import fi.monopoly.client.session.ClientSessionUpdates;
import fi.monopoly.client.session.SessionCommandPort;
import fi.monopoly.server.session.SessionCommandPublisher;
import fi.monopoly.server.session.SessionRegistry;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.sse.SseClient;
import io.javalin.json.JavalinJackson;
import lombok.extern.slf4j.Slf4j;

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
    }

    public void start() {
        app = Javalin.create(config -> {
            config.useVirtualThreads = true;
            config.jsonMapper(new JavalinJackson(objectMapper, false));
        });

        // CORS — unconditional, matching old behavior (browsers require Origin header anyway)
        app.before(ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type");
        });
        app.options("/*", ctx -> ctx.status(204));

        app.get("/health", ctx -> ctx.json(Map.of("status", "ok")));
        app.get("/openapi.yaml", ctx -> {
            ctx.contentType("text/yaml");
            var stream = getClass().getResourceAsStream("/openapi.yaml");
            if (stream != null) ctx.result(stream);
            else ctx.status(404).result("openapi.yaml not found");
        });
        app.get("/docs", ctx -> {
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
            app.post("/command", ctx -> handleCommandFor(ctx, commandPort));
            app.get("/snapshot", ctx -> ctx.json(snapshotSupplier.get()));
            app.sse("/events", client -> streamEvents(client, sessionUpdates, snapshotSupplier));
        }

        // Multi-session endpoints
        if (registry != null) {
            app.get("/sessions", ctx -> ctx.json(registry.list()));
            app.post("/sessions", this::handleSessionsCreate);
            app.post("/sessions/{id}/command", ctx ->
                    handleCommandFor(ctx, requireSession(ctx)));
            app.get("/sessions/{id}/snapshot", ctx ->
                    ctx.json(requireSession(ctx).currentSnapshot()));
            app.sse("/sessions/{id}/events", client -> {
                Optional<SessionCommandPublisher> pub = registry.get(client.ctx().pathParam("id"));
                if (pub.isEmpty()) return;
                streamEvents(client, pub.get(), pub.get()::currentSnapshot);
            });
        }

        app.start(port);
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
            SessionCommand command = commandMapper.fromJson(ctx.bodyAsBytes());
            CommandResult result = port.handle(command);
            ctx.status(result.accepted() ? 200 : 422)
               .json(new CommandResultView(result.accepted(), result.rejections()));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error handling command", e);
            ctx.status(500).json(Map.of("error", "Internal server error"));
        }
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
                        catch (IllegalArgumentException e) { return fi.monopoly.domain.session.BotDifficulty.NORMAL; }
                    }).toList();

            String sessionId = registry.create(names, colors, seatKinds, difficulties);
            ctx.status(201).json(Map.of("sessionId", sessionId));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating session", e);
            ctx.status(500).json(Map.of("error", "Internal server error"));
        }
    }

    // -------------------------------------------------------------------------
    // SSE
    // -------------------------------------------------------------------------

    /**
     * Sets up an SSE connection: subscribes to session updates, sends the initial snapshot,
     * then calls {@link SseClient#keepAlive()} to hold the connection open asynchronously.
     * Future snapshots are pushed directly from the listener callback. The listener is removed
     * when the client disconnects (via the {@code onClose} hook).
     */
    private void streamEvents(
            SseClient client,
            ClientSessionUpdates updates,
            Supplier<ClientSessionSnapshot> snapshotSupplier) {
        ClientSessionListener listener = snapshot -> {
            if (!client.terminated()) {
                try {
                    client.sendEvent(snapshot);
                } catch (Exception e) {
                    client.close();
                }
            }
        };
        updates.addListener(listener);
        client.onClose(() -> updates.removeListener(listener));
        try {
            client.sendEvent(snapshotSupplier.get());
        } catch (Exception ignored) {}
        client.keepAlive();
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
