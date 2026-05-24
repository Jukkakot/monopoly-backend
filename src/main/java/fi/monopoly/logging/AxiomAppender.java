package fi.monopoly.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AxiomAppender extends AppenderBase<ILoggingEvent> {

    private String token;
    private String dataset;
    private String env = "local";

    private final List<Map<String, Object>> buffer = new ArrayList<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "axiom-flusher");
        t.setDaemon(true);
        return t;
    });

    public void setToken(String token)     { this.token = token; }
    public void setDataset(String dataset) { this.dataset = dataset; }
    public void setEnv(String env)         { this.env = env; }

    @Override
    public void start() {
        super.start();
        if (token == null || token.isBlank() || dataset == null || dataset.isBlank()) {
            addWarn("AxiomAppender: AXIOM_TOKEN or AXIOM_DATASET not set — appender disabled");
            addWarn("  AXIOM_TOKEN=" + (token == null ? "null" : (token.isBlank() ? "EMPTY" : "***SET")));
            addWarn("  AXIOM_DATASET=" + (dataset == null ? "null" : (dataset.isBlank() ? "EMPTY" : "***SET")));
            addWarn("  APP_ENV=" + env);
            return;
        }
        addInfo("AxiomAppender started: dataset=" + dataset + ", env=" + env);
        scheduler.scheduleAtFixedRate(this::flush, 5, 5, TimeUnit.SECONDS);
    }

    @Override
    protected synchronized void append(ILoggingEvent event) {
        if (!isStarted()) {
            addWarn("Appender not started, discarding: " + event.getMessage());
            return;
        }
        Map<String, Object> entry = new HashMap<>();
        entry.put("_time", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        entry.put("level", event.getLevel().toString());
        entry.put("logger", event.getLoggerName());
        entry.put("message", event.getFormattedMessage());
        entry.put("env", env);

        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            if (mdc.containsKey("session")) entry.put("session", mdc.get("session"));
            if (mdc.containsKey("actor")) entry.put("actor", mdc.get("actor"));
            if (mdc.containsKey("phase")) entry.put("phase", mdc.get("phase"));
        }

        IThrowableProxy t = event.getThrowableProxy();
        if (t != null) {
            entry.put("exception", t.getClassName() + ": " + t.getMessage());
        }

        buffer.add(entry);
        if (buffer.size() >= 100) {
            addInfo("Buffer reached 100, flushing immediately");
            flush();
        }
    }

    private synchronized void flush() {
        if (buffer.isEmpty()) return;
        if (token == null || token.isBlank() || dataset == null || dataset.isBlank()) {
            return; // Silently skip if not configured
        }
        List<Map<String, Object>> batch = new ArrayList<>(buffer);
        buffer.clear();
        try {
            String json = mapper.writeValueAsString(batch);
            addInfo("Flushing " + batch.size() + " events to Axiom (" + json.length() + " bytes)");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://eu-central-1.aws.edge.axiom.co/v1/ingest/" + dataset))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            httpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(res -> {
                        if (res.statusCode() >= 400) {
                            addError("Axiom ingest rejected: HTTP " + res.statusCode());
                        } else if (res.statusCode() == 200) {
                            addInfo("Axiom ingest accepted: HTTP " + res.statusCode());
                        } else {
                            addInfo("Axiom ingest response: HTTP " + res.statusCode());
                        }
                    })
                    .exceptionally(ex -> { addError("Axiom ingest failed", ex); return null; });
        } catch (Exception e) {
            addError("Failed to flush logs to Axiom", e);
        }
    }

    @Override
    public void stop() {
        flush();
        scheduler.shutdown();
        super.stop();
    }
}
