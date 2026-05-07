package fi.monopoly.server.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fi.monopoly.application.command.SessionCommand;
import fi.monopoly.application.result.CommandRejection;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.client.session.SessionCommandPort;
import fi.monopoly.domain.session.SessionState;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * {@link SessionCommandPort} implementation that delegates over HTTP to a
 * {@link SessionHttpServer} instance.
 *
 * <p>This is the client-side transport complement to {@link SessionHttpServer}: the desktop client
 * can swap the embedded {@link fi.monopoly.host.session.local.EmbeddedDesktopSessionHost} for this
 * adapter to talk to a remote host without changing any of the five presentation-layer adapters
 * that depend on {@link SessionCommandPort}.</p>
 *
 * <p>{@link #currentState()} always returns {@code null} for this transport adapter — snapshot
 * state reaches the client through the SSE stream ({@code GET /events}) via
 * {@link HttpClientSessionUpdates}, not through a synchronous pull.</p>
 */
@Slf4j
public final class HttpSessionCommandPort implements SessionCommandPort {

    private final String commandUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SessionCommandSerializer commandSerializer;

    /**
     * Single-session constructor. Sends commands to {@code baseUrl/command}.
     *
     * @param baseUrl  server base URL, e.g. {@code http://localhost:8080} (no trailing slash)
     */
    public HttpSessionCommandPort(String baseUrl) {
        this.commandUrl = baseUrl + "/command";
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        this.commandSerializer = new SessionCommandSerializer(objectMapper);
    }

    /**
     * Multi-session factory. Targets {@code baseUrl/sessions/{sessionId}/command}.
     */
    public static HttpSessionCommandPort forSession(String baseUrl, String sessionId) {
        return new HttpSessionCommandPort(baseUrl, sessionId);
    }

    /** Private constructor for multi-session target URL. */
    private HttpSessionCommandPort(String baseUrl, String sessionId) {
        this.commandUrl = baseUrl + "/sessions/" + sessionId + "/command";
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        this.commandSerializer = new SessionCommandSerializer(objectMapper);
    }

    @Override
    public CommandResult handle(SessionCommand command) {
        String json;
        try {
            json = commandSerializer.toJson(command);
        } catch (IOException e) {
            log.error("Failed to serialize command {}", command.getClass().getSimpleName(), e);
            return rejected("SERIALIZATION_ERROR", "Failed to serialize command");
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(commandUrl))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json; charset=UTF-8")
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return parseCommandResult(response);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("HTTP command request failed", e);
            return rejected("TRANSPORT_ERROR", "HTTP request failed: " + e.getMessage());
        }
    }

    /**
     * Always returns {@code null}: snapshot state is delivered asynchronously through the SSE
     * stream, not via this synchronous poll.
     */
    @Override
    public SessionState currentState() {
        return null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CommandResult parseCommandResult(HttpResponse<String> response) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
            boolean accepted = Boolean.TRUE.equals(body.get("accepted"));
            @SuppressWarnings("unchecked")
            List<Map<String, String>> rawRejections = (List<Map<String, String>>) body.getOrDefault("rejections", List.of());
            List<CommandRejection> rejections = rawRejections.stream()
                    .map(r -> new CommandRejection(r.get("code"), r.get("message")))
                    .toList();
            return new CommandResult(accepted, null, List.of(), rejections, List.of());
        } catch (Exception e) {
            log.error("Failed to parse command result from server", e);
            return rejected("PARSE_ERROR", "Failed to parse server response");
        }
    }

    private static CommandResult rejected(String code, String message) {
        return new CommandResult(false, null, List.of(), List.of(new CommandRejection(code, message)), List.of());
    }
}
