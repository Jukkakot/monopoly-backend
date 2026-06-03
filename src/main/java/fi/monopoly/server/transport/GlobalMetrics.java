package fi.monopoly.server.transport;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide counters for Prometheus-style /metrics exposure.
 *
 * <p>All state is static so that any component (publishers, registry) can record
 * events without requiring injection of a shared instance.</p>
 */
public final class GlobalMetrics {

    private GlobalMetrics() {}

    private static final long START_TIME_MS = System.currentTimeMillis();

    private static final AtomicLong commandsTotal      = new AtomicLong(0);
    private static final AtomicLong commandsAccepted   = new AtomicLong(0);
    private static final AtomicLong commandsRejected   = new AtomicLong(0);
    private static final AtomicLong sessionsCreated    = new AtomicLong(0);
    private static volatile double  lastCpuLoad        = -1.0;
    private static volatile int     lastSessionCount   = 0;

    public static void recordCommand(boolean accepted) {
        commandsTotal.incrementAndGet();
        if (accepted) commandsAccepted.incrementAndGet();
        else          commandsRejected.incrementAndGet();
    }

    public static void recordSessionCreated() {
        sessionsCreated.incrementAndGet();
    }

    public static void recordLoad(int sessionCount, double cpuLoad) {
        lastSessionCount = sessionCount;
        lastCpuLoad = cpuLoad;
    }

    public static String prometheusText(int activeSessions) {
        long uptimeSeconds = (System.currentTimeMillis() - START_TIME_MS) / 1000;
        return "# HELP monopoly_commands_total Total commands received\n"
             + "# TYPE monopoly_commands_total counter\n"
             + "monopoly_commands_total " + commandsTotal.get() + "\n"
             + "# HELP monopoly_commands_accepted_total Accepted commands\n"
             + "# TYPE monopoly_commands_accepted_total counter\n"
             + "monopoly_commands_accepted_total " + commandsAccepted.get() + "\n"
             + "# HELP monopoly_commands_rejected_total Rejected commands\n"
             + "# TYPE monopoly_commands_rejected_total counter\n"
             + "monopoly_commands_rejected_total " + commandsRejected.get() + "\n"
             + "# HELP monopoly_sessions_created_total Sessions created since startup\n"
             + "# TYPE monopoly_sessions_created_total counter\n"
             + "monopoly_sessions_created_total " + sessionsCreated.get() + "\n"
             + "# HELP monopoly_sessions_active Currently active sessions\n"
             + "# TYPE monopoly_sessions_active gauge\n"
             + "monopoly_sessions_active " + activeSessions + "\n"
             + "# HELP monopoly_uptime_seconds Server uptime in seconds\n"
             + "# TYPE monopoly_uptime_seconds gauge\n"
             + "monopoly_uptime_seconds " + uptimeSeconds + "\n"
             + "# HELP monopoly_cpu_load JVM process CPU load (0.0–1.0, -1 if unavailable)\n"
             + "# TYPE monopoly_cpu_load gauge\n"
             + "monopoly_cpu_load " + String.format("%.4f", lastCpuLoad) + "\n";
    }
}
