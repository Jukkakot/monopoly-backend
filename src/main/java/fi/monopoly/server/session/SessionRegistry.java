package fi.monopoly.server.session;

import fi.monopoly.application.session.InMemorySessionState;
import fi.monopoly.application.session.SessionApplicationService;
import fi.monopoly.domain.session.*;
import fi.monopoly.server.transport.GlobalMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private record Entry(SessionCommandPublisher publisher, InMemorySessionState baseStore, List<String> playerNames, PureDomainBotDriver botDriver) {}

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
        String sessionId = SessionIdGenerator.generate();
        SessionState initialState = PureDomainSessionFactory.initialGameState(sessionId, names, colors, seatKinds);
        InMemorySessionState baseStore = new InMemorySessionState(initialState);
        SessionApplicationService service = PureDomainSessionFactory.create(sessionId, baseStore);
        SessionCommandPublisher publisher = new SessionCommandPublisher(service);
        Map<String, BotDifficulty> difficultyMap = buildDifficultyMap(initialState, difficulties);
        PureDomainBotDriver botDriver = PureDomainBotDriver.createAndRegisterIfNeeded(
                publisher, initialState, difficultyMap);
        sessions.put(sessionId, new Entry(publisher, baseStore, List.copyOf(names), botDriver));
        lastActivityAt.put(sessionId, System.currentTimeMillis());
        GlobalMetrics.recordSessionCreated();
        return sessionId;
    }

    /**
     * Creates a new lobby session with {@code seatCount} unclaimed seats.
     * Players join via {@link #joinLobby} and the host starts via {@link #startLobbyGame}.
     */
    public String createLobby(int seatCount, List<String> colors) {
        String sessionId = SessionIdGenerator.generate();
        SessionState initialState = PureDomainSessionFactory.lobbyInitialState(sessionId, seatCount, colors);
        InMemorySessionState baseStore = new InMemorySessionState(initialState);
        SessionApplicationService service = PureDomainSessionFactory.create(sessionId, baseStore);
        SessionCommandPublisher publisher = new SessionCommandPublisher(service);
        sessions.put(sessionId, new Entry(publisher, baseStore, List.of(), null));
        lastActivityAt.put(sessionId, System.currentTimeMillis());
        GlobalMetrics.recordSessionCreated();
        return sessionId;
    }

    /**
     * Claims the first available (unjoined) seat in a LOBBY session.
     *
     * @return the claimed {@link SeatState} with updated name/color, or empty if no seat available
     */
    public java.util.Optional<SeatState> joinLobby(String sessionId, String name, String color) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) return java.util.Optional.empty();
        lastActivityAt.put(sessionId, System.currentTimeMillis());

        final SeatState[] claimed = {null};
        entry.baseStore().update(state -> {
            if (state.status() != SessionStatus.LOBBY) return state;
            SeatState free = state.seats().stream()
                    .filter(s -> !s.joined())
                    .findFirst()
                    .orElse(null);
            if (free == null) return state;
            String effectiveColor = (color != null && !color.isBlank()) ? color : free.tokenColorHex();
            SeatState updated = new SeatState(free.seatId(), free.seatIndex(), free.playerId(),
                    free.seatKind(), free.controlMode(), name, free.controllerProfileId(),
                    effectiveColor, true);
            claimed[0] = updated;
            List<SeatState> newSeats = state.seats().stream()
                    .map(s -> s.seatId().equals(free.seatId()) ? updated : s)
                    .toList();
            // Update player displayName too
            List<PlayerSnapshot> newPlayers = state.players().stream()
                    .map(p -> p.seatId().equals(free.seatId())
                            ? new PlayerSnapshot(p.playerId(), p.seatId(), name, p.cash(),
                                    p.boardIndex(), p.bankrupt(), p.eliminated(), p.inJail(),
                                    p.jailRoundsRemaining(), p.getOutOfJailCards(), p.ownedPropertyIds())
                            : p)
                    .toList();
            return state.toBuilder().seats(newSeats).players(newPlayers).build();
        });
        if (claimed[0] == null) return java.util.Optional.empty();
        entry.publisher().notifyListeners();
        return java.util.Optional.of(claimed[0]);
    }

    /**
     * Starts a lobby game: converts unjoined seats to BOT seats, determines turn order,
     * transitions status to {@code IN_PROGRESS}, and starts bot drivers.
     *
     * @return false if session not found, not in LOBBY state, or fewer than 2 seats joined
     */
    public boolean startLobbyGame(String sessionId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) return false;
        lastActivityAt.put(sessionId, System.currentTimeMillis());

        SessionState current = entry.baseStore().get();
        if (current.status() != SessionStatus.LOBBY) return false;
        long joinedCount = current.seats().stream().filter(SeatState::joined).count();
        if (joinedCount < 2) return false;

        // Build new game state from joined players + convert unjoined to bots
        List<String> names = current.seats().stream()
                .map(s -> s.joined() ? s.displayName() : "Botti " + (s.seatIndex() + 1))
                .toList();
        List<String> colors = current.seats().stream().map(SeatState::tokenColorHex).toList();
        List<SeatKind> kinds = current.seats().stream()
                .map(s -> s.joined() ? SeatKind.HUMAN : SeatKind.BOT)
                .toList();

        SessionState gameState = PureDomainSessionFactory.initialGameState(sessionId, names, colors, kinds);
        entry.baseStore().update(ignored -> gameState);

        Map<String, BotDifficulty> diffMap = new HashMap<>();
        PureDomainBotDriver botDriver = PureDomainBotDriver.createAndRegisterIfNeeded(
                entry.publisher(), gameState, diffMap);
        sessions.put(sessionId, new Entry(entry.publisher(), entry.baseStore(),
                names.stream().filter(n -> !n.startsWith("Botti ")).toList(), botDriver));

        entry.publisher().notifyListeners();
        return true;
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
                log.info("Evicting idle session {} (idle > {} min)", entry.getKey(), TTL_MINUTES);
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
