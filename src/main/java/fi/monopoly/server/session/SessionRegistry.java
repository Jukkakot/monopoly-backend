package fi.monopoly.server.session;

import fi.monopoly.application.session.SessionApplicationService;
import fi.monopoly.domain.session.BotDifficulty;
import fi.monopoly.domain.session.SeatKind;
import fi.monopoly.domain.session.SeatState;
import fi.monopoly.domain.session.SessionState;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Thread-safe registry of active game sessions for the multi-session server mode.
 *
 * <p>Sessions are created on demand via {@link #create} and identified by a random UUID.
 * The registry owns the {@link SessionCommandPublisher} lifecycle for each session.
 * If any seat is {@link SeatKind#BOT}, a {@link PureDomainBotDriver} is also started.</p>
 *
 * <p>Sessions are automatically evicted after being idle for
 * {@code monopoly.session.ttl.minutes} minutes (default 120). Activity is tracked
 * whenever a publisher is fetched via {@link #get}.</p>
 */
@Slf4j
public final class SessionRegistry {

    private static final long TTL_MINUTES =
            Long.getLong("monopoly.session.ttl.minutes", 120L);
    private static final long CLEANUP_INTERVAL_MINUTES = 5L;

    private record Entry(SessionCommandPublisher publisher, List<String> playerNames, PureDomainBotDriver botDriver) {}

    private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastActivityAt = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;

    public SessionRegistry() {
        cleaner = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("session-cleaner").factory());
        cleaner.scheduleAtFixedRate(this::evictIdleSessions,
                CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * Creates a new all-human session with the given player names and colours.
     */
    public String create(List<String> names, List<String> colors) {
        return create(names, colors, List.of());
    }

    /**
     * Creates a new session with explicit seat kinds. Seats not covered by {@code seatKinds}
     * default to {@link SeatKind#HUMAN}. Bot seats get a {@link PureDomainBotDriver} attached.
     */
    public String create(List<String> names, List<String> colors, List<SeatKind> seatKinds) {
        return create(names, colors, seatKinds, List.of());
    }

    /**
     * Creates a new session with explicit seat kinds and per-seat bot difficulties.
     * {@code difficulties} is indexed by seat position; missing entries default to
     * {@link BotDifficulty#NORMAL}.
     */
    public String create(List<String> names, List<String> colors, List<SeatKind> seatKinds,
                         List<BotDifficulty> difficulties) {
        String sessionId = UUID.randomUUID().toString();
        SessionState initialState = PureDomainSessionFactory.initialGameState(sessionId, names, colors, seatKinds);
        SessionApplicationService service = PureDomainSessionFactory.create(sessionId, initialState);
        SessionCommandPublisher publisher = new SessionCommandPublisher(service);
        Map<String, BotDifficulty> difficultyMap = buildDifficultyMap(initialState, difficulties);
        PureDomainBotDriver botDriver = PureDomainBotDriver.createAndRegisterIfNeeded(
                publisher, initialState, difficultyMap);
        sessions.put(sessionId, new Entry(publisher, List.copyOf(names), botDriver));
        lastActivityAt.put(sessionId, System.currentTimeMillis());
        return sessionId;
    }

    private static Map<String, BotDifficulty> buildDifficultyMap(
            SessionState state, List<BotDifficulty> difficulties) {
        Map<String, BotDifficulty> map = new HashMap<>();
        List<SeatState> seats = state.seats();
        for (int i = 0; i < seats.size(); i++) {
            if (i < difficulties.size() && difficulties.get(i) != null) {
                map.put(seats.get(i).playerId(), difficulties.get(i));
            }
        }
        return map;
    }

    public Optional<SessionCommandPublisher> get(String sessionId) {
        Entry entry = sessions.get(sessionId);
        if (entry != null) {
            lastActivityAt.put(sessionId, System.currentTimeMillis());
        }
        return Optional.ofNullable(entry).map(Entry::publisher);
    }

    public List<SessionSummary> list() {
        return sessions.entrySet().stream()
                .map(e -> new SessionSummary(
                        e.getKey(),
                        e.getValue().playerNames(),
                        e.getValue().publisher().currentState().status()))
                .collect(Collectors.toList());
    }

    public void remove(String sessionId) {
        Entry entry = sessions.remove(sessionId);
        lastActivityAt.remove(sessionId);
        if (entry != null && entry.botDriver() != null) {
            entry.botDriver().stop();
        }
    }

    /** Stops all bot drivers, clears all sessions, and shuts down the cleanup scheduler. */
    public void shutdown() {
        cleaner.shutdownNow();
        sessions.values().forEach(e -> {
            if (e.botDriver() != null) e.botDriver().stop();
        });
        sessions.clear();
        lastActivityAt.clear();
    }

    // -------------------------------------------------------------------------
    // TTL cleanup
    // -------------------------------------------------------------------------

    private void evictIdleSessions() {
        long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(TTL_MINUTES);
        lastActivityAt.entrySet().removeIf(entry -> {
            if (entry.getValue() < cutoff) {
                log.info("Evicting idle session {} (idle > {} min)", entry.getKey().substring(0, 8), TTL_MINUTES);
                Entry session = sessions.remove(entry.getKey());
                if (session != null && session.botDriver() != null) {
                    session.botDriver().stop();
                }
                return true;
            }
            return false;
        });
    }
}
