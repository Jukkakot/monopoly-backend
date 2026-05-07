package fi.monopoly.server.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fi.monopoly.client.session.ClientSessionListener;
import fi.monopoly.client.session.ClientSessionSnapshot;
import fi.monopoly.client.session.ClientSessionUpdates;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ClientSessionUpdates} implementation that connects to the {@link SessionHttpServer}
 * SSE endpoint ({@code GET /events}) and forwards received snapshots to all registered listeners.
 *
 * <p>Call {@link #connect()} once to start the background SSE reader thread. Call
 * {@link #disconnect()} to stop it and close the connection.</p>
 */
@Slf4j
public final class HttpClientSessionUpdates implements ClientSessionUpdates {

    private final String eventsUrl;
    private final ObjectMapper objectMapper;
    private final Set<ClientSessionListener> listeners = new LinkedHashSet<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread sseThread;

    /**
     * Single-session constructor. Connects to {@code baseUrl/events}.
     *
     * @param baseUrl  server base URL, e.g. {@code http://localhost:8080} (no trailing slash)
     */
    public HttpClientSessionUpdates(String baseUrl) {
        this.eventsUrl = baseUrl + "/events";
        this.objectMapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    /**
     * Multi-session factory. Connects to {@code baseUrl/sessions/{sessionId}/events}.
     */
    public static HttpClientSessionUpdates forSession(String baseUrl, String sessionId) {
        return new HttpClientSessionUpdates(baseUrl, sessionId);
    }

    /** Private constructor for multi-session target URL. */
    private HttpClientSessionUpdates(String baseUrl, String sessionId) {
        this.eventsUrl = baseUrl + "/sessions/" + sessionId + "/events";
        this.objectMapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    /**
     * Starts the background SSE reader thread. Idempotent — safe to call multiple times.
     */
    public void connect() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        sseThread = Thread.ofVirtual().name("session-sse-client").start(this::runSseLoop);
    }

    /**
     * Stops the background SSE reader thread. Idempotent — safe to call even if not connected.
     */
    public void disconnect() {
        running.set(false);
        if (sseThread != null) {
            sseThread.interrupt();
            sseThread = null;
        }
    }

    @Override
    public void addListener(ClientSessionListener listener) {
        if (listener != null) {
            synchronized (listeners) {
                listeners.add(listener);
            }
        }
    }

    @Override
    public void removeListener(ClientSessionListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    // -------------------------------------------------------------------------
    // Internal SSE loop
    // -------------------------------------------------------------------------

    private void runSseLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                connectAndRead();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("SSE connection lost, reconnecting in 2s: {}", e.getMessage());
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void connectAndRead() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(eventsUrl))
                .GET()
                .header("Accept", "text/event-stream")
                .build();
        HttpResponse<java.io.InputStream> response = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("SSE connect failed: HTTP " + response.statusCode());
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String json = line.substring("data: ".length());
                    dispatchSnapshot(json);
                }
                // lines starting with ":" are heartbeat comments — ignore
            }
        }
    }

    private void dispatchSnapshot(String json) {
        try {
            ClientSessionSnapshot snapshot = objectMapper.readValue(json, ClientSessionSnapshot.class);
            List<ClientSessionListener> snapshot_listeners;
            synchronized (listeners) {
                snapshot_listeners = List.copyOf(listeners);
            }
            for (ClientSessionListener listener : snapshot_listeners) {
                listener.onSnapshotChanged(snapshot);
            }
        } catch (Exception e) {
            log.error("Failed to parse SSE snapshot: {}", json, e);
        }
    }
}
