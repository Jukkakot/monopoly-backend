package fi.monopoly.server.session;

import fi.monopoly.application.session.InMemorySessionState;
import fi.monopoly.application.session.SessionApplicationService;
import fi.monopoly.domain.session.*;
import fi.monopoly.server.transport.GlobalMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
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
 */
@Slf4j
public final class SessionRegistry {

    private static final int MAX_SEATS = 6;
    private static final long TTL_MINUTES =
            Long.getLong("monopoly.session.ttl.minutes", 120L);
    private static final long CLEANUP_INTERVAL_MINUTES = 5L;

    public record CreateResult(String sessionId, String hostToken, String hostPlayerId, String hostPlayerToken) {}
    public record JoinResult(SeatState seat, String playerToken) {}

    private record Entry(
            SessionCommandPublisher publisher,
            InMemorySessionState baseStore,
            List<String> playerNames,
            PureDomainBotDriver botDriver,
            String hostToken,
            ConcurrentHashMap<String, String> playerTokens
    ) {}

    private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastActivityAt = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;

    public SessionRegistry() {
        cleaner = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("session-cleaner").factory());
        cleaner.scheduleAtFixedRate(this::evictIdleSessions,
                CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    // -------------------------------------------------------------------------
    // Direct-start sessions (no lobby phase)
    // -------------------------------------------------------------------------

    public CreateResult create(List<String> names, List<String> colors) {
        return create(names, colors, List.of());
    }

    public CreateResult create(List<String> names, List<String> colors, List<SeatKind> seatKinds) {
        return create(names, colors, seatKinds, List.of());
    }

    public CreateResult create(List<String> names, List<String> colors, List<SeatKind> seatKinds,
                               List<BotDifficulty> difficulties) {
        String sessionId = SessionIdGenerator.generate();
        boolean allBots = !seatKinds.isEmpty() && seatKinds.stream().allMatch(k -> k == SeatKind.BOT);
        String hostToken = allBots ? null : UUID.randomUUID().toString();
        SessionState initialState = PureDomainSessionFactory.initialGameState(sessionId, names, colors, seatKinds, difficulties);
        InMemorySessionState baseStore = new InMemorySessionState(initialState);
        SessionApplicationService service = PureDomainSessionFactory.create(sessionId, baseStore);
        SessionCommandPublisher publisher = new SessionCommandPublisher(service);
        baseStore.setOnChange(publisher::publishSnapshot);
        Map<String, BotDifficulty> difficultyMap = buildDifficultyMap(initialState, difficulties);
        PureDomainBotDriver botDriver = PureDomainBotDriver.createAndRegisterIfNeeded(
                publisher, initialState, difficultyMap);
        sessions.put(sessionId, new Entry(publisher, baseStore, List.copyOf(names), botDriver, hostToken, new ConcurrentHashMap<>()));
        lastActivityAt.put(sessionId, System.currentTimeMillis());
        GlobalMetrics.recordSessionCreated();
        return new CreateResult(sessionId, hostToken, null, null);
    }

    // -------------------------------------------------------------------------
    // Lobby sessions — new dynamic flow
    // -------------------------------------------------------------------------

    /**
     * Creates a new lobby with the host immediately joined as the first seat.
     * Returns session credentials for both host (token) and host-as-player (playerId + playerToken).
     */
    public CreateResult createLobby(String hostName, String hostColor) {
        String sessionId = SessionIdGenerator.generate();
        String hostToken = UUID.randomUUID().toString();
        String hostPlayerId = "player-" + UUID.randomUUID();
        String effectiveColor = (hostColor != null && !hostColor.isBlank())
                ? hostColor : PureDomainSessionFactory.SEAT_COLORS.get(0);

        SessionState initialState = PureDomainSessionFactory.lobbyWithHost(sessionId, hostPlayerId, hostName, effectiveColor);
        InMemorySessionState baseStore = new InMemorySessionState(initialState);
        SessionApplicationService service = PureDomainSessionFactory.create(sessionId, baseStore);
        SessionCommandPublisher publisher = new SessionCommandPublisher(service);
        baseStore.setOnChange(publisher::publishSnapshot);

        ConcurrentHashMap<String, String> playerTokens = new ConcurrentHashMap<>();
        String hostPlayerToken = UUID.randomUUID().toString();
        playerTokens.put(hostPlayerId, hostPlayerToken);

        sessions.put(sessionId, new Entry(publisher, baseStore, new ArrayList<>(), null, hostToken, playerTokens));
        lastActivityAt.put(sessionId, System.currentTimeMillis());
        GlobalMetrics.recordSessionCreated();
        return new CreateResult(sessionId, hostToken, hostPlayerId, hostPlayerToken);
    }

    /**
     * Dynamically adds a new human seat for the joining player (max {@value MAX_SEATS} total).
     */
    public Optional<JoinResult> joinLobby(String sessionId, String name, String color) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) return Optional.empty();
        lastActivityAt.put(sessionId, System.currentTimeMillis());

        String newPlayerId = "player-" + UUID.randomUUID();
        final SeatState[] added = {null};

        entry.baseStore().update(state -> {
            if (state.status() != SessionStatus.LOBBY) return state;
            if (state.seats().size() >= MAX_SEATS) return state;

            int seatIndex = state.seats().size();
            String seatId = "seat-" + UUID.randomUUID();
            String effectiveColor = resolveColor(color, state, seatIndex);
            SeatState newSeat = new SeatState(seatId, seatIndex, newPlayerId, SeatKind.HUMAN,
                    ControlMode.MANUAL, name, "HUMAN", effectiveColor, true, null, false);
            added[0] = newSeat;

            List<SeatState> newSeats = new ArrayList<>(state.seats());
            newSeats.add(newSeat);
            List<PlayerSnapshot> newPlayers = new ArrayList<>(state.players());
            newPlayers.add(new PlayerSnapshot(newPlayerId, seatId, name, 1500, 0, false, false, false, 0, 0, List.of()));
            return state.toBuilder().seats(newSeats).players(newPlayers).build();
        });

        if (added[0] == null) return Optional.empty();
        String playerToken = UUID.randomUUID().toString();
        entry.playerTokens().put(newPlayerId, playerToken);
        entry.publisher().notifyListeners();
        return Optional.of(new JoinResult(added[0], playerToken));
    }

    /**
     * Adds a bot seat to the lobby (host-only action, max {@value MAX_SEATS} total).
     *
     * @return the added bot seat, or empty if at capacity or not in LOBBY state
     */
    public Optional<SeatState> addLobbyBot(String sessionId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) return Optional.empty();
        lastActivityAt.put(sessionId, System.currentTimeMillis());

        String botPlayerId = "player-" + UUID.randomUUID();
        final SeatState[] added = {null};

        entry.baseStore().update(state -> {
            if (state.status() != SessionStatus.LOBBY) return state;
            if (state.seats().size() >= MAX_SEATS) return state;

            int seatIndex = state.seats().size();
            String botName = uniqueBotName(state);
            String seatId = "seat-" + UUID.randomUUID();
            String color = resolveColor(null, state, seatIndex);
            SeatState botSeat = new SeatState(seatId, seatIndex, botPlayerId, SeatKind.BOT,
                    ControlMode.AUTOPLAY, botName, "BOT", color, true, BotDifficulty.STRONG, true);
            added[0] = botSeat;

            List<SeatState> newSeats = new ArrayList<>(state.seats());
            newSeats.add(botSeat);
            List<PlayerSnapshot> newPlayers = new ArrayList<>(state.players());
            newPlayers.add(new PlayerSnapshot(botPlayerId, seatId, botName, 1500, 0, false, false, false, 0, 0, List.of()));
            return state.toBuilder().seats(newSeats).players(newPlayers).build();
        });

        if (added[0] == null) return Optional.empty();
        entry.publisher().notifyListeners();
        return Optional.of(added[0]);
    }

    /**
     * Removes a bot seat from the lobby (host-only action).
     *
     * @return true if the bot was found and removed
     */
    public boolean removeLobbyBot(String sessionId, String seatId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) return false;
        lastActivityAt.put(sessionId, System.currentTimeMillis());

        final boolean[] removed = {false};
        entry.baseStore().update(state -> {
            if (state.status() != SessionStatus.LOBBY) return state;
            SeatState target = state.seats().stream()
                    .filter(s -> s.seatId().equals(seatId) && s.seatKind() == SeatKind.BOT)
                    .findFirst().orElse(null);
            if (target == null) return state;

            removed[0] = true;
            List<SeatState> newSeats = state.seats().stream()
                    .filter(s -> !s.seatId().equals(seatId))
                    .collect(Collectors.toList());
            List<PlayerSnapshot> newPlayers = state.players().stream()
                    .filter(p -> !p.playerId().equals(target.playerId()))
                    .collect(Collectors.toList());
            return state.toBuilder().seats(newSeats).players(newPlayers).build();
        });

        if (removed[0]) entry.publisher().notifyListeners();
        return removed[0];
    }

    /**
     * Sets a player's ready status. When all human players are ready, the game starts automatically.
     *
     * @return true if the ready state was changed
     */
    public boolean setPlayerReady(String sessionId, String playerId, boolean ready) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) return false;
        lastActivityAt.put(sessionId, System.currentTimeMillis());

        final boolean[] changed = {false};
        entry.baseStore().update(state -> {
            if (state.status() != SessionStatus.LOBBY) return state;
            SeatState seat = state.seats().stream()
                    .filter(s -> s.playerId().equals(playerId) && s.seatKind() == SeatKind.HUMAN)
                    .findFirst().orElse(null);
            if (seat == null || seat.ready() == ready) return state;

            changed[0] = true;
            SeatState updated = new SeatState(seat.seatId(), seat.seatIndex(), seat.playerId(),
                    seat.seatKind(), seat.controlMode(), seat.displayName(), seat.controllerProfileId(),
                    seat.tokenColorHex(), seat.joined(), seat.botDifficulty(), ready);
            List<SeatState> newSeats = state.seats().stream()
                    .map(s -> s.playerId().equals(playerId) ? updated : s)
                    .toList();
            return state.toBuilder().seats(newSeats).build();
        });

        if (!changed[0]) return false;

        // Check if all human players are now ready → auto-start
        SessionState current = entry.baseStore().get();
        List<SeatState> humans = current.seats().stream()
                .filter(s -> s.seatKind() == SeatKind.HUMAN).toList();
        boolean allReady = !humans.isEmpty() && humans.stream().allMatch(SeatState::ready);
        long totalPlayers = current.seats().size();

        if (allReady && totalPlayers >= 2) {
            startLobbyGame(sessionId, entry);
        } else {
            entry.publisher().notifyListeners();
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Bot failsafe
    // -------------------------------------------------------------------------

    /**
     * Retrigers the bot driver for the session, forcing it to re-evaluate its turn.
     * Used by the host when a bot appears stuck. Returns true if a bot driver exists.
     */
    public boolean retriggerBot(String sessionId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null || entry.botDriver() == null) return false;
        entry.botDriver().retrigger();
        return true;
    }

    // -------------------------------------------------------------------------
    // Lobby validation helpers
    // -------------------------------------------------------------------------

    /** Returns true if the given name (case-insensitive) is already taken in the lobby. */
    public boolean isNameTakenInLobby(String sessionId, String name) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) return false;
        return entry.baseStore().get().seats().stream()
                .anyMatch(s -> s.displayName().equalsIgnoreCase(name));
    }

    // -------------------------------------------------------------------------
    // Token validation
    // -------------------------------------------------------------------------

    public boolean validatePlayerToken(String sessionId, String playerId, String token) {
        Entry entry = sessions.get(sessionId);
        if (entry == null || entry.playerTokens().isEmpty()) return true;
        return token != null && token.equals(entry.playerTokens().get(playerId));
    }

    public boolean validateHostToken(String sessionId, String token) {
        Entry entry = sessions.get(sessionId);
        if (entry == null || entry.hostToken() == null) return true;
        return token != null && token.equals(entry.hostToken());
    }

    // -------------------------------------------------------------------------
    // Internal game start
    // -------------------------------------------------------------------------

    private void startLobbyGame(String sessionId, Entry entry) {
        SessionState current = entry.baseStore().get();
        if (current.status() != SessionStatus.LOBBY) return;

        List<SeatState> lobbySeats = current.seats();
        String hostPlayerId = current.hostPlayerId();

        SessionState gameState = PureDomainSessionFactory.initialGameStateFromSeats(sessionId, lobbySeats, hostPlayerId);
        entry.baseStore().update(ignored -> gameState);

        Map<String, BotDifficulty> diffMap = new HashMap<>();
        gameState.seats().stream()
                .filter(s -> s.seatKind() == SeatKind.BOT)
                .forEach(s -> diffMap.put(s.playerId(), BotDifficulty.STRONG));
        PureDomainBotDriver botDriver = PureDomainBotDriver.createAndRegisterIfNeeded(
                entry.publisher(), gameState, diffMap);

        List<String> humanNames = gameState.seats().stream()
                .filter(s -> s.seatKind() == SeatKind.HUMAN)
                .map(SeatState::displayName).toList();
        sessions.put(sessionId, new Entry(entry.publisher(), entry.baseStore(),
                humanNames, botDriver, entry.hostToken(), entry.playerTokens()));

        entry.publisher().notifyListeners();
    }

    // -------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------

    public boolean setBotSpeed(String sessionId, double multiplier) {
        Entry entry = sessions.get(sessionId);
        if (entry == null || entry.botDriver() == null) return false;
        entry.botDriver().setSpeedMultiplier(multiplier);
        return true;
    }

    public double getBotSpeedMultiplier(String sessionId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null || entry.botDriver() == null) return -1;
        return entry.botDriver().getSpeedMultiplier();
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

    public void shutdown() {
        cleaner.shutdownNow();
        sessions.values().forEach(e -> {
            if (e.botDriver() != null) e.botDriver().stop();
        });
        sessions.clear();
        lastActivityAt.clear();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static final List<String> BOT_NAME_POOL = List.of(
        "Teräs-Ville", "Pii-Risto", "Kisko-Kai", "Servo-Sanna", "Nano-Nea",
        "Diodi-Dixi", "Mega-Manu", "Pikku-Pirkka", "Turbo-Teuvo", "Klonkku-Klaus",
        "Rele-Reijo", "Piiri-Pirjo", "Robotti-Rauli", "Silppu-Silja", "Moottori-Matti"
    );

    /** Picks a random bot name not already used in the current lobby. */
    private static String uniqueBotName(SessionState state) {
        java.util.Set<String> used = state.seats().stream()
                .map(SeatState::displayName).collect(java.util.stream.Collectors.toSet());
        List<String> pool = new java.util.ArrayList<>(BOT_NAME_POOL);
        java.util.Collections.shuffle(pool, java.util.concurrent.ThreadLocalRandom.current());
        for (String n : pool) {
            if (!used.contains(n)) return n;
        }
        // All pool names taken — fall back with a numeric suffix
        for (int i = 1; i <= 10; i++) {
            String fallback = "Botti-" + i;
            if (!used.contains(fallback)) return fallback;
        }
        return "Botti";
    }

    /** Returns the next palette color not yet used by any seat in the current state.
     *  If a specific color is requested, uses it only when not already taken; otherwise auto-picks. */
    private static String resolveColor(String requested, SessionState state, int seatIndex) {
        List<String> palette = PureDomainSessionFactory.SEAT_COLORS;
        java.util.Set<String> used = state.seats().stream()
                .map(SeatState::tokenColorHex)
                .map(String::toUpperCase)
                .collect(java.util.stream.Collectors.toSet());
        if (requested != null && !requested.isBlank() && !used.contains(requested.toUpperCase())) {
            return requested;
        }
        for (String c : palette) {
            if (!used.contains(c.toUpperCase())) return c;
        }
        return palette.get(seatIndex % palette.size());
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
