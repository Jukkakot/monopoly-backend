package fi.monopoly.application.session.purchase;

import fi.monopoly.application.session.InMemorySessionState;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.types.SpotType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DomainPropertyPurchaseGatewayTest {

    private static final String SESSION_ID = "test-session";
    private static final String PLAYER_ID = "player-1";
    private static final String PROPERTY_ID = "B1";

    @Test
    void buyPropertyDeductsPriceAndRecordsOwnership() {
        int price = SpotType.B1.getIntegerProperty("price");
        InMemorySessionState store = storeWith(
                List.of(player(PLAYER_ID, 1500)),
                List.of(unownedProperty(PROPERTY_ID))
        );
        DomainPropertyPurchaseGateway gateway = new DomainPropertyPurchaseGateway(store);

        boolean result = gateway.buyProperty(PLAYER_ID, PROPERTY_ID);

        assertTrue(result);
        PlayerSnapshot updated = playerById(store.get(), PLAYER_ID);
        assertEquals(1500 - price, updated.cash());
        assertTrue(updated.ownedPropertyIds().contains(PROPERTY_ID));

        PropertyStateSnapshot prop = propertyById(store.get(), PROPERTY_ID);
        assertEquals(PLAYER_ID, prop.ownerPlayerId());
    }

    @Test
    void buyPropertyReturnsFalseForUnknownPropertyId() {
        InMemorySessionState store = storeWith(
                List.of(player(PLAYER_ID, 1500)),
                List.of()
        );
        DomainPropertyPurchaseGateway gateway = new DomainPropertyPurchaseGateway(store);

        boolean result = gateway.buyProperty(PLAYER_ID, "NONEXISTENT");

        assertFalse(result);
    }

    @Test
    void buyPropertyDoesNotAffectOtherPlayers() {
        int price = SpotType.B1.getIntegerProperty("price");
        InMemorySessionState store = storeWith(
                List.of(player(PLAYER_ID, 1500), player("player-2", 800)),
                List.of(unownedProperty(PROPERTY_ID))
        );
        DomainPropertyPurchaseGateway gateway = new DomainPropertyPurchaseGateway(store);

        gateway.buyProperty(PLAYER_ID, PROPERTY_ID);

        assertEquals(800, playerById(store.get(), "player-2").cash());
    }

    private static InMemorySessionState storeWith(
            List<PlayerSnapshot> players,
            List<PropertyStateSnapshot> properties
    ) {
        List<SeatState> seats = players.stream()
                .map(p -> {
                    int idx = players.indexOf(p);
                    return new SeatState("seat-" + idx, idx, p.playerId(),
                            SeatKind.HUMAN, ControlMode.MANUAL, p.name(), "HUMAN", "#FF0000");
                })
                .toList();
        SessionState state = new SessionState(
                SESSION_ID, 0L, SessionStatus.IN_PROGRESS,
                seats, players, properties,
                new TurnState(null, TurnPhase.WAITING_FOR_ROLL, false, false),
                null, null, null, null, null
        );
        return new InMemorySessionState(state);
    }

    private static PlayerSnapshot player(String playerId, int cash) {
        int idx = Integer.parseInt(playerId.replace("player-", "")) - 1;
        return new PlayerSnapshot(playerId, "seat-" + idx, playerId, cash, 0, false, false, false, 0, 0, List.of());
    }

    private static PropertyStateSnapshot unownedProperty(String propertyId) {
        return new PropertyStateSnapshot(propertyId, null, false, 0, 0);
    }

    private static PlayerSnapshot playerById(SessionState state, String playerId) {
        return state.players().stream().filter(p -> p.playerId().equals(playerId)).findFirst().orElseThrow();
    }

    private static PropertyStateSnapshot propertyById(SessionState state, String propertyId) {
        return state.properties().stream().filter(p -> p.propertyId().equals(propertyId)).findFirst().orElseThrow();
    }
}
