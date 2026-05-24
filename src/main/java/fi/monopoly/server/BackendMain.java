package fi.monopoly.server;

import fi.monopoly.server.session.SessionRegistry;
import fi.monopoly.server.transport.SessionHttpServer;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CountDownLatch;

/**
 * Standalone backend entry point for Render.com (or any container environment).
 *
 * <p>Reads the {@code PORT} environment variable (default 8080), creates a
 * {@link SessionRegistry}, and starts a {@link SessionHttpServer} in multi-session
 * mode only (no single-session compatibility endpoints). Blocks until SIGTERM.</p>
 */
@Slf4j
public final class BackendMain {

    private BackendMain() {}

    public static void main(String[] args) throws InterruptedException {
        int port = resolvePort();
        SessionRegistry registry = new SessionRegistry();
        SessionHttpServer server = new SessionHttpServer(null, null, null, port, registry);

        server.start();
        log.info("Monopoly backend started on port {}", port);
        
        // Debug Axiom configuration
        String axiomToken = System.getenv("AXIOM_TOKEN");
        String axiomDataset = System.getenv("AXIOM_DATASET");
        String appEnv = System.getenv("APP_ENV");
        String logLevel = System.getenv("LOG_LEVEL");
        log.info("Logging configuration: LOG_LEVEL={}, APP_ENV={}", logLevel, appEnv);
        log.info("Axiom status: token={}, dataset={}", 
            axiomToken == null ? "NOT_SET" : (axiomToken.isBlank() ? "EMPTY" : "***SET"),
            axiomDataset == null ? "NOT_SET" : (axiomDataset.isBlank() ? "EMPTY" : axiomDataset));

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — stopping server");
            registry.shutdown();
            server.stop();
            shutdownLatch.countDown();
        }, "shutdown-hook"));

        shutdownLatch.await();
    }

    private static int resolvePort() {
        String env = System.getenv("PORT");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException e) {
                log.warn("Invalid PORT env var '{}', using 10000", env);
            }
        }
        return 10000;
    }
}
