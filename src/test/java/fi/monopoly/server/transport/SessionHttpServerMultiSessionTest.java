package fi.monopoly.server.transport;

import fi.monopoly.server.session.SessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionHttpServerMultiSessionTest {

    private int port;
    private SessionHttpServer server;
    private SessionRegistry registry;

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

    @Test
    void healthEndpoint_includesSessionCount() throws Exception {
        String body = get("/health").body();
        assertTrue(body.contains("\"status\":\"ok\""), "should contain status:ok");
        assertTrue(body.contains("\"sessions\":"), "should contain sessions count");
        assertTrue(body.contains("\"uptimeSeconds\":"), "should contain uptimeSeconds");
        assertTrue(body.contains("\"version\":"), "should contain version");
    }

    @Test
    void healthEndpoint_sessionCountReflectsActiveSessions() throws Exception {
        String before = get("/health").body();
        assertTrue(before.contains("\"sessions\":0"), "should start at 0 sessions");

        registry.create(List.of("Alice", "Bob"), List.of());

        String after = get("/health").body();
        assertTrue(after.contains("\"sessions\":1"), "should reflect 1 session after creation");
    }

    @Test
    void metricsEndpoint_returnsPrometheusFormat() throws Exception {
        HttpResponse<String> response = get("/metrics");
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("monopoly_commands_total"), "should have commands counter");
        assertTrue(body.contains("monopoly_sessions_active"), "should have active sessions gauge");
        assertTrue(body.contains("monopoly_uptime_seconds"), "should have uptime gauge");
        assertTrue(body.contains("# TYPE"), "should have TYPE annotations");
        assertTrue(body.contains("# HELP"), "should have HELP annotations");
    }

    @Test
    void metricsEndpoint_contentTypeIsPlainText() throws Exception {
        HttpResponse<String> response = get("/metrics");
        assertTrue(
                response.headers().firstValue("Content-Type").orElse("").contains("text/plain"),
                "metrics content-type should be text/plain"
        );
    }

    @Test
    void deleteSession_returns204AndSessionIsGone() throws Exception {
        String sessionId = registry.create(List.of("Alice", "Bob"), List.of());

        HttpResponse<String> deleteResponse = delete("/sessions/" + sessionId);
        assertEquals(204, deleteResponse.statusCode());

        // Second delete must return 404
        HttpResponse<String> secondDelete = delete("/sessions/" + sessionId);
        assertEquals(404, secondDelete.statusCode());
    }

    @Test
    void deleteSession_unknownId_returns404() throws Exception {
        HttpResponse<String> response = delete("/sessions/00000000-0000-0000-0000-000000000000");
        assertEquals(404, response.statusCode());
    }

    @Test
    void deleteSession_removedSessionNoLongerInList() throws Exception {
        String sessionId = registry.create(List.of("Alice", "Bob"), List.of());

        String beforeDelete = get("/sessions").body();
        assertTrue(beforeDelete.contains(sessionId));

        delete("/sessions/" + sessionId);

        String afterDelete = get("/sessions").body();
        assertFalse(afterDelete.contains(sessionId));
    }

    @Test
    void corsHeadersIncludeDelete() throws Exception {
        HttpResponse<String> response = get("/health");
        String allowMethods = response.headers().firstValue("Access-Control-Allow-Methods").orElse("");
        assertTrue(allowMethods.contains("DELETE"), "CORS should allow DELETE");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        return client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        return client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
