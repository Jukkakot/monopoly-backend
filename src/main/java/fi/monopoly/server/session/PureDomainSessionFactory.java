package fi.monopoly.server.session;

import fi.monopoly.application.session.InMemorySessionState;
import fi.monopoly.application.session.OverlaySessionStateStore;
import fi.monopoly.application.session.SessionApplicationService;
import fi.monopoly.application.session.StartingOrderDeterminer;
import fi.monopoly.application.session.auction.DomainAuctionGateway;
import fi.monopoly.application.session.debt.DomainDebtRemediationGateway;
import fi.monopoly.application.session.leave.DomainLeaveGameGateway;
import fi.monopoly.application.session.purchase.DomainPropertyPurchaseGateway;
import fi.monopoly.application.session.trade.DomainTradeGateway;
import fi.monopoly.application.session.turn.DomainTurnActionGateway;
import fi.monopoly.application.session.turn.DomainTurnContinuationGateway;
import fi.monopoly.application.session.turn.CardDeckLoader;
import fi.monopoly.utils.RandomSource;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.types.SpotType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Creates a {@link SessionApplicationService} wired with pure domain gateway implementations.
 *
 * <p>This factory does not depend on any Processing runtime objects. It is the foundation
 * for running a standalone session server (see {@link StartSessionServer}).</p>
 *
 * <h2>Current limitations</h2>
 * <ul>
 *   <li>Bot bidding strategy is simplified (max bid = cash) vs. the legacy strategy which
 *       applies property-valuation multipliers and per-bot reserves.</li>
 *   <li>{@code DebtOpeningGateway} / {@code SessionPaymentPort} still use legacy
 *       {@code PaymentRequest}; the desktop host wires these separately for the legacy path.</li>
 * </ul>
 */
public final class PureDomainSessionFactory {

    private PureDomainSessionFactory() {}

    /**
     * Creates a fully wired {@link SessionApplicationService} with the given initial state.
     *
     * <p>Gateway implementations that are not yet pure-domain are left unconfigured; commands
     * routed to those handlers will be rejected with {@code UNSUPPORTED_COMMAND}.</p>
     */
    public static SessionApplicationService create(String sessionId, SessionState initialState) {
        return create(sessionId, new InMemorySessionState(initialState));
    }

    public static SessionApplicationService create(String sessionId, InMemorySessionState store) {
        return create(sessionId, store, RandomSource.threadLocal());
    }

    /**
     * Creates a wired {@link SessionApplicationService} with an injected {@link RandomSource}.
     *
     * <p>Using a seeded source (via {@link RandomSource#seeded(long)}) makes the game fully
     * deterministic: same seed → same dice sequence → byte-identical play-through. This is the
     * entry point used by the evaluation harness.</p>
     */
    public static SessionApplicationService create(String sessionId, InMemorySessionState store, RandomSource randomSource) {
        OverlaySessionStateStore overlay = new OverlaySessionStateStore(store::get);

        // Auctions and trades live exclusively in the overlay layer at runtime — the
        // gateways never write them to the base store. If an initial state carries one
        // (scenario/test injection), lift it into the overlay and strip it from the base;
        // otherwise clearing it via the overlay (set to null) would fall back to the
        // base copy and resurrect the auction/trade forever.
        SessionState injected = store.get();
        if (injected.auctionState() != null || injected.tradeState() != null) {
            overlay.setAuctionState(injected.auctionState());
            overlay.setTradeState(injected.tradeState());
            store.update(s -> s.toBuilder().auctionState(null).tradeState(null).build());
        }

        SessionApplicationService service = new SessionApplicationService(sessionId, overlay);
        service.configureAuctionFlow(new DomainAuctionGateway(store));
        service.configurePropertyPurchaseFlow(new DomainPropertyPurchaseGateway(store));

        DomainTurnContinuationGateway continuationGateway = new DomainTurnContinuationGateway(store);
        service.configureTurnContinuationFlow(continuationGateway);

        DomainTurnActionGateway turnActionGateway = new DomainTurnActionGateway(
                store,
                (playerId, propertyId, displayName, price, message, continuation) ->
                        service.openPropertyPurchaseDecision(playerId, propertyId, displayName, price, message, continuation),
                randomSource
        );
        service.configureTurnActionFlow(turnActionGateway);
        service.configurePostPropertyPurchasePause(turnActionGateway::pauseAfterPropertyPurchase);

        service.configureDebtRemediationFlow(new DomainDebtRemediationGateway(store));
        service.configureTradeFlow(new DomainTradeGateway(store));
        service.configureLeaveGame(new DomainLeaveGameGateway(store));

        return service;
    }

    /**
     * Builds a playable initial game state for the given player names.
     *
     * <p>Each player receives €1500 starting cash. Seat order (= turn order) is determined by a
     * simulated dice roll: the highest roller becomes seat 0 and goes first. Ties among the
     * top-rolling players are resolved by re-rolling only the tied group, recursively. This matches
     * the standard Finnish Monopoly starting-order rule.</p>
     *
     * @param sessionId   the session identifier
     * @param playerNames ordered list of player display names (2–4 players)
     * @param colors      ordered list of player colour hex strings (e.g. "#FF0000")
     */
    public static SessionState initialGameState(String sessionId, List<String> playerNames, List<String> colors) {
        return initialGameState(sessionId, playerNames, colors, List.of(), new Random());
    }

    /**
     * Creates an initial game state with explicit seat kinds.
     *
     * @param seatKinds per-player seat kinds; if shorter than playerNames the remaining seats default to {@link SeatKind#HUMAN}
     */
    public static SessionState initialGameState(String sessionId, List<String> playerNames, List<String> colors, List<SeatKind> seatKinds) {
        return initialGameState(sessionId, playerNames, colors, seatKinds, new Random());
    }

    /** Package-private variant used in tests to pass a seeded {@link Random}. */
    static SessionState initialGameState(String sessionId, List<String> playerNames, List<String> colors, Random rng) {
        return initialGameState(sessionId, playerNames, colors, List.of(), rng);
    }

    /** Full variant with seat kinds and seeded RNG — used by tests and overloads above. */
    static SessionState initialGameState(String sessionId, List<String> playerNames, List<String> colors,
                                         List<SeatKind> seatKinds, Random rng) {
        if (playerNames.isEmpty()) throw new IllegalArgumentException("At least one player is required");

        List<PropertyStateSnapshot> properties = SpotType.SPOT_TYPES.stream()
                .filter(s -> s.isProperty)
                .map(s -> new PropertyStateSnapshot(s.name(), null, false, 0, 0))
                .toList();

        // Determine turn order by dice roll (standard Monopoly starting-order rule).
        List<Integer> inputIndices = new ArrayList<>();
        for (int i = 0; i < playerNames.size(); i++) inputIndices.add(i);
        List<Integer> turnOrder = determineStartOrder(inputIndices, rng);

        List<SeatState> seats = new ArrayList<>();
        List<PlayerSnapshot> players = new ArrayList<>();
        for (int seatIndex = 0; seatIndex < turnOrder.size(); seatIndex++) {
            int originalIndex = turnOrder.get(seatIndex);
            String name = playerNames.get(originalIndex);
            String color = originalIndex < colors.size() ? colors.get(originalIndex) : "#AAAAAA";
            String playerId = "player-" + (originalIndex + 1);
            String seatId = "seat-" + seatIndex;
            SeatKind kind = originalIndex < seatKinds.size() ? seatKinds.get(originalIndex) : SeatKind.HUMAN;
            String profile = kind == SeatKind.BOT ? "BOT" : "HUMAN";
            seats.add(new SeatState(seatId, seatIndex, playerId, kind, ControlMode.MANUAL, name, profile, color, true));
            players.add(new PlayerSnapshot(playerId, seatId, name, 1500, 0, false, false, false, 0, 0, List.of()));
        }
        seats = sanitizeColors(seats);

        String firstPlayerId = players.get(0).playerId();
        List<String> chanceDeck = CardDeckLoader.buildDeck("chance", rng);
        List<String> communityDeck = CardDeckLoader.buildDeck("community", rng);
        return SessionState.builder()
                .sessionId(sessionId)
                .version(0L)
                .status(SessionStatus.IN_PROGRESS)
                .seats(seats)
                .players(players)
                .properties(properties)
                .turn(new TurnState(firstPlayerId, TurnPhase.WAITING_FOR_ROLL, true, false, 0))
                .chanceDeck(chanceDeck)
                .communityDeck(communityDeck)
                .hostPlayerId("player-1")
                .build();
    }

    // -------------------------------------------------------------------------
    // Private: starting order determination
    // -------------------------------------------------------------------------

    /**
     * Returns {@code candidates} sorted from highest dice roll to lowest.
     *
     * <p>Each candidate rolls two dice (1–6 each). Players with different rolls are ordered by
     * their roll value (highest first). Any subset of candidates that tied on the same value is
     * resolved by a recursive re-roll of only those tied candidates. The resulting list is a
     * total ordering — the first element goes first in the game.</p>
     */
    static List<Integer> determineStartOrder(List<Integer> candidates, Random rng) {
        return StartingOrderDeterminer.determineStartOrder(candidates, rng);
    }

    /** Color palette for dynamically assigned seats (index 0–5). */
    static final List<String> SEAT_COLORS = List.of(
            "#E53935", "#1E88E5", "#43A047", "#F9A825", "#8E24AA", "#FF7043");

    /**
     * Builds a LOBBY state with a single host seat already joined.
     * The host's playerId is used as hostPlayerId in the returned state.
     */
    public static SessionState lobbyWithHost(String sessionId, String hostPlayerId, String hostName, String hostColor) {
        String seatId = "seat-0";
        SeatState hostSeat = new SeatState(seatId, 0, hostPlayerId, SeatKind.HUMAN, ControlMode.MANUAL,
                hostName, "HUMAN", hostColor, true, false);
        PlayerSnapshot hostPlayer = new PlayerSnapshot(hostPlayerId, seatId, hostName,
                1500, 0, false, false, false, 0, 0, List.of());
        List<PropertyStateSnapshot> properties = SpotType.SPOT_TYPES.stream()
                .filter(s -> s.isProperty)
                .map(s -> new PropertyStateSnapshot(s.name(), null, false, 0, 0))
                .toList();
        return SessionState.builder()
                .sessionId(sessionId)
                .version(0L)
                .status(SessionStatus.LOBBY)
                .seats(List.of(hostSeat))
                .players(List.of(hostPlayer))
                .properties(properties)
                .turn(new TurnState(null, TurnPhase.WAITING_FOR_ROLL, false, false, 0))
                .hostPlayerId(hostPlayerId)
                .build();
    }

    /**
     * Builds a playable initial game state from an existing set of lobby seats, preserving
     * player IDs. Turn order is determined by the standard Monopoly dice-roll rule.
     */
    public static SessionState initialGameStateFromSeats(String sessionId, List<SeatState> lobbySeats, String hostPlayerId) {
        return initialGameStateFromSeats(sessionId, lobbySeats, hostPlayerId, new Random());
    }

    static SessionState initialGameStateFromSeats(String sessionId, List<SeatState> lobbySeats, String hostPlayerId, Random rng) {
        List<Integer> inputIndices = new ArrayList<>();
        for (int i = 0; i < lobbySeats.size(); i++) inputIndices.add(i);
        List<Integer> turnOrder = determineStartOrder(inputIndices, rng);

        List<SeatState> seats = new ArrayList<>();
        List<PlayerSnapshot> players = new ArrayList<>();
        for (int seatIndex = 0; seatIndex < turnOrder.size(); seatIndex++) {
            int originalIndex = turnOrder.get(seatIndex);
            SeatState lobby = lobbySeats.get(originalIndex);
            SeatState gameSeat = new SeatState(
                    "seat-" + seatIndex, seatIndex,
                    lobby.playerId(), lobby.seatKind(), lobby.controlMode(),
                    lobby.displayName(), lobby.controllerProfileId(),
                    lobby.tokenColorHex(), true, false);
            seats.add(gameSeat);
            players.add(new PlayerSnapshot(lobby.playerId(), "seat-" + seatIndex,
                    lobby.displayName(), 1500, 0, false, false, false, 0, 0, List.of()));
        }

        String firstPlayerId = players.get(0).playerId();
        List<String> chanceDeck = CardDeckLoader.buildDeck("chance", rng);
        List<String> communityDeck = CardDeckLoader.buildDeck("community", rng);
        return SessionState.builder()
                .sessionId(sessionId)
                .version(0L)
                .status(SessionStatus.IN_PROGRESS)
                .seats(seats)
                .players(players)
                .properties(SpotType.SPOT_TYPES.stream()
                        .filter(s -> s.isProperty)
                        .map(s -> new PropertyStateSnapshot(s.name(), null, false, 0, 0))
                        .toList())
                .turn(new TurnState(firstPlayerId, TurnPhase.WAITING_FOR_ROLL, true, false, 0))
                .chanceDeck(chanceDeck)
                .communityDeck(communityDeck)
                .hostPlayerId(hostPlayerId)
                .build();
    }

    /**
     * Builds a LOBBY state with {@code seatCount} unclaimed seats.
     *
     * <p>Each seat is pre-assigned a colour but has no player name yet ({@code joined=false}).
     * Players claim seats via the {@code POST /sessions/{id}/join} endpoint. The session
     * transitions to {@code IN_PROGRESS} when {@code POST /sessions/{id}/start} is called.</p>
     */
    public static SessionState lobbyInitialState(String sessionId, List<String> names,
                                                   List<String> colors, List<SeatKind> kinds) {
        int seatCount = names.size();
        List<SeatState> seats = new ArrayList<>();
        List<PlayerSnapshot> players = new ArrayList<>();
        List<PropertyStateSnapshot> properties = SpotType.SPOT_TYPES.stream()
                .filter(s -> s.isProperty)
                .map(s -> new PropertyStateSnapshot(s.name(), null, false, 0, 0))
                .toList();
        for (int i = 0; i < seatCount; i++) {
            String color = i < colors.size() ? colors.get(i) : "#AAAAAA";
            String name = i < names.size() ? names.get(i) : null;
            SeatKind kind = i < kinds.size() ? kinds.get(i) : SeatKind.HUMAN;
            String playerId = "player-" + (i + 1);
            String seatId = "seat-" + i;
            boolean isBot = kind == SeatKind.BOT;
            // Bot seats are pre-filled; human seats wait for players to join
            seats.add(new SeatState(seatId, i, playerId, kind, isBot ? ControlMode.AUTOPLAY : ControlMode.MANUAL,
                    isBot ? name : null, isBot ? "BOT" : "HUMAN", color, isBot));
            players.add(new PlayerSnapshot(playerId, seatId, isBot ? (name != null ? name : "") : "", 1500, 0, false, false, false, 0, 0, List.of()));
        }
        return SessionState.builder()
                .sessionId(sessionId)
                .version(0L)
                .status(SessionStatus.LOBBY)
                .seats(seats)
                .players(players)
                .properties(properties)
                .turn(new TurnState(null, TurnPhase.WAITING_FOR_ROLL, false, false, 0))
                .build();
    }

    /**
     * Ensures no two seats share the same tokenColorHex. Duplicate/null colors are replaced
     * with the next unused entry from {@link #SEAT_COLORS}. First occurrence of each color wins.
     * Package-private so {@code SessionRegistry} can share this implementation.
     */
    static List<SeatState> sanitizeColors(List<SeatState> seats) {
        Set<String> usedColors = new HashSet<>();
        List<SeatState> result = new ArrayList<>(seats.size());
        for (SeatState seat : seats) {
            String color = seat.tokenColorHex();
            String key = (color != null && !color.isBlank()) ? color.toUpperCase() : null;
            if (key == null || !usedColors.add(key)) {
                color = SEAT_COLORS.stream()
                        .filter(c -> !usedColors.contains(c.toUpperCase()))
                        .findFirst()
                        .orElse(SEAT_COLORS.get(result.size() % SEAT_COLORS.size()));
                usedColors.add(color.toUpperCase());
            }
            result.add(new SeatState(seat.seatId(), seat.seatIndex(), seat.playerId(),
                    seat.seatKind(), seat.controlMode(), seat.displayName(),
                    seat.controllerProfileId(), color, seat.joined(), seat.ready()));
        }
        return result;
    }

    /**
     * Builds a minimal valid starting state for a session with no players and no properties.
     *
     * <p>Suitable for tests and smoke runs.</p>
     */
    public static SessionState emptyInitialState(String sessionId) {
        return new SessionState(
                sessionId, 0L, SessionStatus.IN_PROGRESS,
                List.of(), List.of(), List.of(),
                new TurnState(null, TurnPhase.WAITING_FOR_ROLL, false, false, 0),
                null, null, null, null, null, null
        );
    }
}
