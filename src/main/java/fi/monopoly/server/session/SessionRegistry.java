package fi.monopoly.server.session;

import fi.monopoly.application.session.InMemorySessionState;
import fi.monopoly.application.session.SessionApplicationService;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.server.transport.DebugStateImport;
import fi.monopoly.server.transport.GlobalMetrics;
import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** Hard cap on concurrent sessions. Configurable via -Dmonopoly.session.max=N. */
    private static final int MAX_SESSIONS =
            Integer.getInteger("monopoly.session.max", 50);
    /**
     * Reject new sessions when the EWMA-smoothed CPU load exceeds this fraction (0.0–1.0).
     * Uses a 2-second EWMA (α=0.5) so brief spikes (e.g. during parallel test runs) do not
     * trigger rejection. With α=0.5 starting from 0.0, reaching this threshold requires
     * roughly 10 seconds of sustained ≥95% CPU load.
     * Configurable via -Dmonopoly.session.cpu.threshold=N.
     */
    private static final double CPU_LOAD_THRESHOLD =
            Double.parseDouble(System.getProperty("monopoly.session.cpu.threshold", "0.95"));
    private static final long CPU_SAMPLE_INTERVAL_SECONDS = 2L;
    private static final long LOAD_LOG_INTERVAL_SECONDS = 30L;

    public record CreateResult(String sessionId, String hostToken, String hostPlayerId, String hostPlayerToken, Map<String, String> allPlayerTokens) {
        public CreateResult(String sessionId, String hostToken, String hostPlayerId, String hostPlayerToken) {
            this(sessionId, hostToken, hostPlayerId, hostPlayerToken, Map.of());
        }
    }
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
    /**
     * EWMA (α=0.5, sampled every CPU_SAMPLE_INTERVAL_SECONDS) of JVM process CPU load.
     * Starts at 0.0 so brief spikes at startup or during parallel test runs do not immediately
     * trigger rejection. Converges to true load after several samples.
     */
    private volatile double smoothedCpu = 0.0;

    public SessionRegistry() {
        cleaner = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("session-cleaner").factory());
        cleaner.scheduleAtFixedRate(this::evictIdleSessions,
                CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
        cleaner.scheduleAtFixedRate(this::updateSmoothedCpu,
                CPU_SAMPLE_INTERVAL_SECONDS, CPU_SAMPLE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        cleaner.scheduleAtFixedRate(this::logSystemLoad,
                LOAD_LOG_INTERVAL_SECONDS, LOAD_LOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // Direct-start sessions (no lobby phase)
    // -------------------------------------------------------------------------

    public CreateResult create(List<String> names, List<String> colors) {
        return create(names, colors, List.of(), null);
    }

    public CreateResult create(List<String> names, List<String> colors, List<SeatKind> seatKinds) {
        return create(names, colors, seatKinds, null);
    }

    public CreateResult create(List<String> names, List<String> colors, List<SeatKind> seatKinds, String botStrategyName) {
        checkCapacity();
        validateNoDuplicateNames(names);
        validateNoDuplicateColors(colors);
        String sessionId = SessionIdGenerator.generate();
        boolean allBots = !seatKinds.isEmpty() && seatKinds.stream().allMatch(k -> k == SeatKind.BOT);
        String hostToken = allBots ? null : UUID.randomUUID().toString();
        SessionState initialState = PureDomainSessionFactory.initialGameState(sessionId, names, colors, seatKinds);
        InMemorySessionState baseStore = new InMemorySessionState(initialState);
        SessionApplicationService service = PureDomainSessionFactory.create(sessionId, baseStore);
        SessionCommandPublisher publisher = new SessionCommandPublisher(service);
        baseStore.setOnChange(publisher::publishSnapshot);
        Map<String, StrongBotConfig> botConfigs = buildBotConfigs(initialState, sessionId);
        fi.monopoly.server.bot.BotStrategy strategy = buildBotStrategy(botConfigs);
        PureDomainBotDriver botDriver = PureDomainBotDriver.createAndRegisterIfNeeded(
                publisher, initialState, botConfigs, strategy);
        if (botDriver != null) botDriver.setViewerGatingEnabled(true);
        // Generate player tokens for all human seats so that commands can be authenticated.
        // Player IDs are deterministic: "player-" + (inputIndex + 1).
        ConcurrentHashMap<String, String> playerTokens = new ConcurrentHashMap<>();
        String firstHumanPlayerId = null;
        String firstHumanPlayerToken = null;
        for (int i = 0; i < seatKinds.size(); i++) {
            if (seatKinds.get(i) == SeatKind.HUMAN) {
                String pid = "player-" + (i + 1);
                String token = UUID.randomUUID().toString();
                playerTokens.put(pid, token);
                if (firstHumanPlayerId == null) {
                    firstHumanPlayerId = pid;
                    firstHumanPlayerToken = token;
                }
            }
        }
        sessions.put(sessionId, new Entry(publisher, baseStore, List.copyOf(names), botDriver, hostToken, playerTokens));
        lastActivityAt.put(sessionId, System.currentTimeMillis());
        GlobalMetrics.recordSessionCreated();
        return new CreateResult(sessionId, hostToken, firstHumanPlayerId, firstHumanPlayerToken, Map.copyOf(playerTokens));
    }

    // -------------------------------------------------------------------------
    // Lobby sessions — new dynamic flow
    // -------------------------------------------------------------------------

    /**
     * Creates a new lobby with the host immediately joined as the first seat.
     * Returns session credentials for both host (token) and host-as-player (playerId + playerToken).
     */
    public CreateResult createLobby(String hostName, String hostColor) {
        return createLobby(hostName, hostColor, null);
    }

    public CreateResult createLobby(String hostName, String hostColor, String botStrategyName) {
        checkCapacity();
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
     * If a bot holds the same color or name as the joining human, the bot is reassigned first.
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

            List<SeatState> workingSeats = new ArrayList<>(state.seats());

            // Reassign any bot that holds the same color as the joining human
            if (color != null && !color.isBlank()) {
                final String colorUp = color.toUpperCase();
                SeatState colorBot = workingSeats.stream()
                        .filter(s -> s.seatKind() == SeatKind.BOT
                                && s.tokenColorHex() != null
                                && s.tokenColorHex().toUpperCase().equals(colorUp))
                        .findFirst().orElse(null);
                if (colorBot != null) {
                    // Pick a new palette color excluding the one the human is about to claim
                    java.util.Set<String> usedExcludingBot = workingSeats.stream()
                            .filter(s -> !s.seatId().equals(colorBot.seatId()))
                            .map(SeatState::tokenColorHex).filter(java.util.Objects::nonNull)
                            .map(String::toUpperCase).collect(java.util.stream.Collectors.toSet());
                    String newBotColor = PureDomainSessionFactory.SEAT_COLORS.stream()
                            .filter(c -> !usedExcludingBot.contains(c.toUpperCase()))
                            .findFirst().orElse(PureDomainSessionFactory.SEAT_COLORS.get(0));
                    SeatState updated = new SeatState(colorBot.seatId(), colorBot.seatIndex(),
                            colorBot.playerId(), colorBot.seatKind(), colorBot.controlMode(),
                            colorBot.displayName(), colorBot.controllerProfileId(),
                            newBotColor, colorBot.joined(), colorBot.ready());
                    workingSeats = workingSeats.stream()
                            .map(s -> s.seatId().equals(colorBot.seatId()) ? updated : s)
                            .collect(Collectors.toList());
                }
            }

            // Reassign any bot that holds the same name as the joining human
            SeatState nameBot = workingSeats.stream()
                    .filter(s -> s.seatKind() == SeatKind.BOT
                            && s.displayName() != null
                            && s.displayName().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
            if (nameBot != null) {
                SessionState tempState = state.toBuilder().seats(workingSeats).build();
                String newBotName = uniqueBotName(tempState);
                SeatState updated = new SeatState(nameBot.seatId(), nameBot.seatIndex(),
                        nameBot.playerId(), nameBot.seatKind(), nameBot.controlMode(),
                        newBotName, nameBot.controllerProfileId(),
                        nameBot.tokenColorHex(), nameBot.joined(), nameBot.ready());
                workingSeats = workingSeats.stream()
                        .map(s -> s.seatId().equals(nameBot.seatId()) ? updated : s)
                        .collect(Collectors.toList());
            }

            int seatIndex = workingSeats.size();
            String seatId = "seat-" + UUID.randomUUID();
            SessionState tempForColor = state.toBuilder().seats(workingSeats).build();
            String effectiveColor = resolveColor(color, tempForColor, seatIndex);
            SeatState newSeat = new SeatState(seatId, seatIndex, newPlayerId, SeatKind.HUMAN,
                    ControlMode.MANUAL, name, "HUMAN", effectiveColor, true, false);
            added[0] = newSeat;

            workingSeats.add(newSeat);
            // Sync player names for any reassigned bots, then add the new human player
            final List<SeatState> finalSeats = workingSeats;
            List<PlayerSnapshot> updatedPlayers = state.players().stream()
                    .map(p -> {
                        SeatState seat = finalSeats.stream()
                                .filter(s -> s.playerId().equals(p.playerId())).findFirst().orElse(null);
                        if (seat != null && !seat.displayName().equals(p.name())) {
                            return new PlayerSnapshot(p.playerId(), p.seatId(), seat.displayName(),
                                    p.cash(), p.boardIndex(), p.bankrupt(), p.eliminated(), p.inJail(),
                                    p.jailRoundsRemaining(), p.getOutOfJailCards(), p.ownedPropertyIds());
                        }
                        return p;
                    }).collect(Collectors.toList());
            updatedPlayers.add(new PlayerSnapshot(newPlayerId, seatId, name, 1500, 0, false, false, false, 0, 0, List.of()));
            return state.toBuilder().seats(workingSeats).players(updatedPlayers).build();
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
                    ControlMode.AUTOPLAY, botName, "BOT", color, true, true);
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
                    seat.tokenColorHex(), seat.joined(), ready);
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

    public void notifySseConnected(String sessionId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null || entry.botDriver() == null) return;
        entry.botDriver().onSseConnected();
    }

    public void notifySseDisconnected(String sessionId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null || entry.botDriver() == null) return;
        entry.botDriver().onSseDisconnected();
    }

    public void notifyClientAck(String sessionId, long version) {
        Entry entry = sessions.get(sessionId);
        if (entry == null || entry.botDriver() == null) return;
        entry.botDriver().onClientAck(version);
    }

    // -------------------------------------------------------------------------
    // Debug state import
    // -------------------------------------------------------------------------

    /**
     * Applies a partial state patch to a live session and broadcasts the new snapshot.
     * Intended for the developer debug panel only — not exposed in production flows.
     *
     * @return true if the patch was applied, false if the session was not found
     */
    public boolean importDebugState(String sessionId, DebugStateImport patch) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) return false;
        lastActivityAt.put(sessionId, System.currentTimeMillis());
        entry.baseStore().update(state -> applyDebugPatch(state, patch));
        entry.publisher().notifyListeners();
        log.info("Debug state import applied to session {}", sessionId.substring(0, Math.min(8, sessionId.length())));
        return true;
    }

    private static SessionState applyDebugPatch(SessionState state, DebugStateImport patch) {
        SessionState.SessionStateBuilder builder = state.toBuilder();

        if (patch.players() != null) {
            java.util.List<PlayerSnapshot> players = new java.util.ArrayList<>(state.players());
            for (DebugStateImport.PlayerPatch pp : patch.players()) {
                for (int i = 0; i < players.size(); i++) {
                    PlayerSnapshot p = players.get(i);
                    if (p.playerId().equals(pp.playerId())) {
                        players.set(i, new PlayerSnapshot(
                                p.playerId(), p.seatId(), p.name(),
                                pp.cash() != null ? pp.cash() : p.cash(),
                                pp.boardIndex() != null ? pp.boardIndex() : p.boardIndex(),
                                pp.bankrupt() != null ? pp.bankrupt() : p.bankrupt(),
                                p.eliminated(),
                                pp.inJail() != null ? pp.inJail() : p.inJail(),
                                pp.jailRoundsRemaining() != null ? pp.jailRoundsRemaining() : p.jailRoundsRemaining(),
                                pp.getOutOfJailCards() != null ? pp.getOutOfJailCards() : p.getOutOfJailCards(),
                                pp.ownedPropertyIds() != null ? pp.ownedPropertyIds() : p.ownedPropertyIds()
                        ));
                        break;
                    }
                }
            }
            builder.players(players);
        }

        if (patch.properties() != null) {
            java.util.List<PropertyStateSnapshot> props = new java.util.ArrayList<>(state.properties());
            for (DebugStateImport.PropertyPatch pp : patch.properties()) {
                for (int i = 0; i < props.size(); i++) {
                    PropertyStateSnapshot prop = props.get(i);
                    if (prop.propertyId().equals(pp.propertyId())) {
                        String owner = pp.ownerPlayerId() != null
                                ? (pp.ownerPlayerId().isEmpty() ? null : pp.ownerPlayerId())
                                : prop.ownerPlayerId();
                        props.set(i, new PropertyStateSnapshot(
                                prop.propertyId(), owner,
                                pp.mortgaged() != null ? pp.mortgaged() : prop.mortgaged(),
                                pp.houseCount() != null ? pp.houseCount() : prop.houseCount(),
                                pp.hotelCount() != null ? pp.hotelCount() : prop.hotelCount()
                        ));
                        break;
                    }
                }
            }
            builder.properties(props);
        }

        if (patch.turn() != null && state.turn() != null) {
            DebugStateImport.TurnPatch tp = patch.turn();
            TurnState cur = state.turn();
            TurnPhase newPhase = tp.phase() != null
                    ? TurnPhase.valueOf(tp.phase())
                    : cur.phase();
            boolean canRoll = newPhase == TurnPhase.WAITING_FOR_ROLL;
            boolean canEndTurn = newPhase == TurnPhase.WAITING_FOR_END_TURN;
            builder.turn(new TurnState(
                    tp.activePlayerId() != null ? tp.activePlayerId() : cur.activePlayerId(),
                    newPhase, canRoll, canEndTurn,
                    tp.consecutiveDoubles() != null ? tp.consecutiveDoubles() : cur.consecutiveDoubles(),
                    tp.lastDice() != null ? tp.lastDice() : cur.lastDice()
            ));
        }

        if (Boolean.TRUE.equals(patch.clearDebt())) builder.activeDebt(null);
        if (Boolean.TRUE.equals(patch.clearDecision())) builder.pendingDecision(null);
        if (Boolean.TRUE.equals(patch.clearAuction())) builder.auctionState(null);
        if (Boolean.TRUE.equals(patch.clearTrade())) builder.tradeState(null);

        // Force next dice roll
        if (patch.nextDice() != null && patch.nextDice().length >= 2) {
            builder.nextDiceOverride(patch.nextDice());
        }
        // Force specific card to front of chance deck
        if (patch.nextChanceCard() != null && !patch.nextChanceCard().isBlank()
                && state.chanceDeck() != null) {
            List<String> deck = new java.util.ArrayList<>(state.chanceDeck());
            deck.remove(patch.nextChanceCard());
            deck.add(0, patch.nextChanceCard());
            builder.chanceDeck(deck);
        }
        // Force specific card to front of community deck
        if (patch.nextCommunityCard() != null && !patch.nextCommunityCard().isBlank()
                && state.communityDeck() != null) {
            List<String> deck = new java.util.ArrayList<>(state.communityDeck());
            deck.remove(patch.nextCommunityCard());
            deck.add(0, patch.nextCommunityCard());
            builder.communityDeck(deck);
        }

        return builder.build();
    }

    // -------------------------------------------------------------------------
    // Lobby validation helpers
    // -------------------------------------------------------------------------

    /** Returns true if the given name (case-insensitive) is already taken by a HUMAN seat in the lobby. */
    public boolean isNameTakenInLobby(String sessionId, String name) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) return false;
        return entry.baseStore().get().seats().stream()
                .filter(s -> s.seatKind() == SeatKind.HUMAN)
                .anyMatch(s -> s.displayName().equalsIgnoreCase(name));
    }

    /** Returns true if the given color is already taken by a HUMAN seat in the lobby. */
    public boolean isColorTakenByHuman(String sessionId, String color) {
        if (color == null || color.isBlank()) return false;
        Entry entry = sessions.get(sessionId);
        if (entry == null) return false;
        final String colorUp = color.toUpperCase();
        return entry.baseStore().get().seats().stream()
                .filter(s -> s.seatKind() == SeatKind.HUMAN)
                .anyMatch(s -> s.tokenColorHex() != null && s.tokenColorHex().toUpperCase().equals(colorUp));
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

        List<SeatState> lobbySeats = PureDomainSessionFactory.sanitizeColors(current.seats());
        String hostPlayerId = current.hostPlayerId();

        SessionState gameState = PureDomainSessionFactory.initialGameStateFromSeats(sessionId, lobbySeats, hostPlayerId);
        entry.baseStore().update(ignored -> gameState);

        Map<String, StrongBotConfig> botConfigs = buildBotConfigs(gameState, sessionId);
        fi.monopoly.server.bot.BotStrategy strategy = buildBotStrategy(botConfigs);
        PureDomainBotDriver botDriver = PureDomainBotDriver.createAndRegisterIfNeeded(
                entry.publisher(), gameState, botConfigs, strategy);
        if (botDriver != null) botDriver.setViewerGatingEnabled(true);

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

    // -------------------------------------------------------------------------
    // Capacity guard & load monitoring
    // -------------------------------------------------------------------------

    /**
     * Throws {@link SessionLimitExceededException} if the server cannot accept a new session.
     * Two checks are performed:
     * <ol>
     *   <li>Hard limit: {@value #MAX_SESSIONS} concurrent sessions (configurable via
     *       {@code -Dmonopoly.session.max=N}).</li>
     *   <li>CPU load: rejects when JVM process CPU exceeds {@link #CPU_LOAD_THRESHOLD}
     *       (configurable via {@code -Dmonopoly.session.cpu.threshold=0.75}).</li>
     * </ol>
     */
    private static void validateNoDuplicateNames(List<String> names) {
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (name == null) continue;
            if (!seen.add(name.trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("DUPLICATE_PLAYER_NAME");
            }
        }
    }

    private static void validateNoDuplicateColors(List<String> colors) {
        Set<String> seen = new HashSet<>();
        for (String color : colors) {
            if (color == null || color.isBlank()) continue;
            if (!seen.add(color.trim().toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("DUPLICATE_PLAYER_COLOR");
            }
        }
    }

    private void checkCapacity() {
        int count = sessions.size();
        if (count >= MAX_SESSIONS) {
            log.warn("session limit reached: count={} max={}", count, MAX_SESSIONS);
            throw new SessionLimitExceededException("MAX_SESSIONS_REACHED",
                    "Server has reached the maximum number of concurrent sessions (" + MAX_SESSIONS + ")");
        }
        // Use the EWMA-smoothed CPU load (updated every CPU_SAMPLE_INTERVAL_SECONDS).
        // Brief spikes (< ~10 s) cannot push smoothedCpu past the threshold; only sustained
        // overload triggers rejection.
        if (smoothedCpu > CPU_LOAD_THRESHOLD) {
            log.warn("cpu load too high: smoothed={}% threshold={}% sessions={}",
                    String.format("%.1f", smoothedCpu * 100), String.format("%.0f", CPU_LOAD_THRESHOLD * 100), count);
            throw new SessionLimitExceededException("SERVER_BUSY",
                    "Server is currently under high load, please try again later");
        }
    }

    /** Returns the JVM process CPU load in [0.0, 1.0], or -1.0 if unavailable. */
    private static double readCpuLoad() {
        try {
            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            return osBean.getProcessCpuLoad();
        } catch (Exception e) {
            return -1.0;
        }
    }

    /** Scheduled every {@value #CPU_SAMPLE_INTERVAL_SECONDS}s to keep smoothedCpu up to date. */
    private void updateSmoothedCpu() {
        double cpu = readCpuLoad();
        if (cpu >= 0) {
            smoothedCpu = smoothedCpu * 0.5 + cpu * 0.5;
        }
    }

    /** Scheduled every {@value #LOAD_LOG_INTERVAL_SECONDS}s to make CPU trends visible in logs. */
    private void logSystemLoad() {
        int count = sessions.size();
        double cpu = readCpuLoad();
        String cpuStr = cpu >= 0 ? String.format("%.1f%%", cpu * 100) : "n/a";
        if (cpu > 0.60 || count > MAX_SESSIONS * 0.80) {
            log.warn("load: cpu={} sessions={}/{}", cpuStr, count, MAX_SESSIONS);
        } else {
            log.info("load: cpu={} sessions={}/{}", cpuStr, count, MAX_SESSIONS);
        }
        GlobalMetrics.recordLoad(count, cpu);
    }

    /**
     * Builds per-bot {@link StrongBotConfig} maps using player-count-aware presets
     * with seat-level diversity.
     *
     * <p>Bot seat 0 gets the optimal preset for the player count. Each subsequent bot
     * gets a ±10 % mutation seeded by sessionId hash ^ seatIndex, giving each bot a
     * distinct but competitive playstyle — preventing exploitable homogeneity.
     */
    private static fi.monopoly.server.bot.BotStrategy buildBotStrategy(
            Map<String, StrongBotConfig> configs) {
        return new PureDomainStrategy(configs);
    }

    private static Map<String, StrongBotConfig> buildBotConfigs(SessionState state, String sessionId) {
        int totalPlayers = state.players().size();
        int sessionSeed  = sessionId.hashCode();
        Map<String, StrongBotConfig> configs = new HashMap<>();
        int botSeatIdx = 0;
        for (SeatState seat : state.seats()) {
            if (seat.seatKind() == SeatKind.BOT && seat.playerId() != null) {
                long seed = (long) sessionSeed ^ ((long) botSeatIdx * 31);
                configs.put(seat.playerId(), StrongBotConfig.forSeat(botSeatIdx, totalPlayers, seed));
                botSeatIdx++;
            }
        }
        return configs;
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
