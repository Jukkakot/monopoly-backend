package fi.monopoly.server.session;

import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.types.SpotType;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 0.2: test builder for {@link SessionState}.
 *
 * <p>Lets unit tests construct arbitrary board states in under 10 lines.
 * Returns a fully-valid {@code SessionState} ready to hand to a handler or strategy.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SessionState state = TestSessionState.twoPlayerGame()
 *     .withCash("player-1", 300)
 *     .withOwnership("player-1", "B1", "B2")
 *     .withOwnership("player-2", "B3")
 *     .withPhase(TurnPhase.WAITING_FOR_END_TURN)
 *     .build();
 * }</pre>
 */
public final class TestSessionState {

    private static final String SESSION_ID = "test-session";

    private final List<PlayerSnapshot> players = new ArrayList<>();
    private final List<SeatState> seats = new ArrayList<>();
    private final List<PropertyStateSnapshot> properties = new ArrayList<>();
    private TurnPhase phase = TurnPhase.WAITING_FOR_ROLL;
    private String activePlayerId = "player-1";
    private boolean canRoll = true;

    private TestSessionState() {
        // Initialise all board properties as unowned.
        SpotType.SPOT_TYPES.stream()
                .filter(s -> s.isProperty)
                .forEach(s -> properties.add(new PropertyStateSnapshot(s.name(), null, false, 0, 0)));
    }

    /** Two-player game with default 1 500 € cash each, no properties owned. */
    public static TestSessionState twoPlayerGame() {
        TestSessionState b = new TestSessionState();
        b.addPlayer("player-1", "Alice", "#E53935");
        b.addPlayer("player-2", "Bob", "#1E88E5");
        return b;
    }

    /** N-player game. Player IDs: player-1 … player-N. */
    public static TestSessionState nPlayerGame(int n) {
        TestSessionState b = new TestSessionState();
        String[] colors = {"#E53935","#1E88E5","#43A047","#F9A825","#8E24AA","#FF7043"};
        String[] names  = {"Alice","Bob","Carol","Dave","Eve","Frank"};
        for (int i = 1; i <= n; i++) {
            b.addPlayer("player-" + i, names[i - 1], colors[(i - 1) % colors.length]);
        }
        return b;
    }

    private void addPlayer(String id, String name, String color) {
        int idx = players.size();
        String seatId = "seat-" + idx;
        seats.add(new SeatState(seatId, idx, id, SeatKind.BOT, ControlMode.AUTOPLAY, name, "BOT", color));
        players.add(new PlayerSnapshot(id, seatId, name, 1500, 0, false, false, false, 0, 0, List.of()));
    }

    /** Override cash for a specific player. */
    public TestSessionState withCash(String playerId, int cash) {
        replacePlayer(playerId, p -> new PlayerSnapshot(p.playerId(), p.seatId(), p.name(),
                cash, p.boardIndex(), p.bankrupt(), p.eliminated(), p.inJail(),
                p.jailRoundsRemaining(), p.getOutOfJailCards(), p.ownedPropertyIds()));
        return this;
    }

    /** Give {@code playerId} ownership of the named properties (SpotType names, e.g. "B1", "B2"). */
    public TestSessionState withOwnership(String playerId, String... propIds) {
        for (String propId : propIds) {
            for (int i = 0; i < properties.size(); i++) {
                if (properties.get(i).propertyId().equals(propId)) {
                    PropertyStateSnapshot old = properties.get(i);
                    properties.set(i, new PropertyStateSnapshot(propId, playerId,
                            old.mortgaged(), old.houseCount(), old.hotelCount()));
                }
            }
            // Add to player's ownedPropertyIds
            replacePlayer(playerId, p -> {
                List<String> owned = new ArrayList<>(p.ownedPropertyIds());
                if (!owned.contains(propId)) owned.add(propId);
                return new PlayerSnapshot(p.playerId(), p.seatId(), p.name(),
                        p.cash(), p.boardIndex(), p.bankrupt(), p.eliminated(), p.inJail(),
                        p.jailRoundsRemaining(), p.getOutOfJailCards(), List.copyOf(owned));
            });
        }
        return this;
    }

    /** Mark a property as mortgaged (owner must already be set via {@link #withOwnership}). */
    public TestSessionState withMortgaged(String propId) {
        for (int i = 0; i < properties.size(); i++) {
            if (properties.get(i).propertyId().equals(propId)) {
                PropertyStateSnapshot old = properties.get(i);
                properties.set(i, new PropertyStateSnapshot(propId, old.ownerPlayerId(),
                        true, old.houseCount(), old.hotelCount()));
            }
        }
        return this;
    }

    /** Set houses on a specific property. */
    public TestSessionState withHouses(String propId, int houses) {
        for (int i = 0; i < properties.size(); i++) {
            if (properties.get(i).propertyId().equals(propId)) {
                PropertyStateSnapshot old = properties.get(i);
                properties.set(i, new PropertyStateSnapshot(propId, old.ownerPlayerId(),
                        old.mortgaged(), houses, 0));
            }
        }
        return this;
    }

    public TestSessionState withPhase(TurnPhase phase) {
        this.phase = phase;
        canRoll = (phase == TurnPhase.WAITING_FOR_ROLL);
        return this;
    }

    public TestSessionState withActivePlayer(String playerId) {
        this.activePlayerId = playerId;
        return this;
    }

    public SessionState build() {
        boolean canEnd = (phase == TurnPhase.WAITING_FOR_END_TURN);
        return SessionState.builder()
                .sessionId(SESSION_ID)
                .version(0L)
                .status(SessionStatus.IN_PROGRESS)
                .seats(List.copyOf(seats))
                .players(List.copyOf(players))
                .properties(List.copyOf(properties))
                .turn(new TurnState(activePlayerId, phase, canRoll, canEnd, 0))
                .hostPlayerId("player-1")
                .build();
    }

    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface PlayerMapper {
        PlayerSnapshot map(PlayerSnapshot p);
    }

    private void replacePlayer(String playerId, PlayerMapper mapper) {
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).playerId().equals(playerId)) {
                players.set(i, mapper.map(players.get(i)));
                return;
            }
        }
    }
}
