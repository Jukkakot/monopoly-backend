package fi.monopoly.application.session.auction;

import fi.monopoly.application.session.InMemorySessionState;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DomainAuctionGatewayTest {

    private static final String SESSION_ID = "test-session";
    private static final String PLAYER_1 = "player-1";
    private static final String PLAYER_2 = "player-2";
    private static final String PLAYER_3 = "player-3";
    private static final String PROPERTY_ID = "B1";

    @Test
    void eligibleBidderIdsIncludesPlayersWithEnoughCash() {
        InMemorySessionState store = storeWithPlayers(
                player(PLAYER_1, 0, 500),
                player(PLAYER_2, 1, 5),
                player(PLAYER_3, 2, 200)
        );
        DomainAuctionGateway gateway = new DomainAuctionGateway(store);

        List<String> eligible = gateway.eligibleBidderIds(PLAYER_1, PROPERTY_ID);

        assertTrue(eligible.contains(PLAYER_1));
        assertFalse(eligible.contains(PLAYER_2));
        assertTrue(eligible.contains(PLAYER_3));
    }

    @Test
    void eligibleBidderIdsExcludesBankruptPlayers() {
        InMemorySessionState store = storeWithPlayers(
                bankruptPlayer(PLAYER_1, 0, 500),
                player(PLAYER_2, 1, 500)
        );
        DomainAuctionGateway gateway = new DomainAuctionGateway(store);

        List<String> eligible = gateway.eligibleBidderIds(PLAYER_1, PROPERTY_ID);

        assertFalse(eligible.contains(PLAYER_1));
        assertTrue(eligible.contains(PLAYER_2));
    }

    @Test
    void eligibleBidderIdsOrderStartsFromTriggeringPlayer() {
        InMemorySessionState store = storeWithPlayers(
                player(PLAYER_1, 0, 500),
                player(PLAYER_2, 1, 500),
                player(PLAYER_3, 2, 500)
        );
        DomainAuctionGateway gateway = new DomainAuctionGateway(store);

        List<String> eligible = gateway.eligibleBidderIds(PLAYER_2, PROPERTY_ID);

        assertEquals(PLAYER_2, eligible.get(0));
        assertEquals(PLAYER_3, eligible.get(1));
        assertEquals(PLAYER_1, eligible.get(2));
    }

    @Test
    void maxBidForReturnsCashAmount() {
        InMemorySessionState store = storeWithPlayers(player(PLAYER_1, 0, 750));
        DomainAuctionGateway gateway = new DomainAuctionGateway(store);

        assertEquals(750, gateway.maxBidFor(PLAYER_1, PROPERTY_ID));
    }

    @Test
    void maxBidForReturnsZeroForUnknownPlayer() {
        InMemorySessionState store = storeWithPlayers(player(PLAYER_1, 0, 500));
        DomainAuctionGateway gateway = new DomainAuctionGateway(store);

        assertEquals(0, gateway.maxBidFor("player-99", PROPERTY_ID));
    }

    @Test
    void nextBidAmountReturnsOpeningBidWhenCashEqualsOpeningBid() {
        InMemorySessionState store = storeWithPlayers(player(PLAYER_1, 0, AuctionGateway.OPENING_BID));
        DomainAuctionGateway gateway = new DomainAuctionGateway(store);

        int next = gateway.nextBidAmount(PLAYER_1, PROPERTY_ID, 0);

        assertEquals(AuctionGateway.OPENING_BID, next);
    }

    @Test
    void nextBidAmountExceedsMinimumWhenCashAllows() {
        InMemorySessionState store = storeWithPlayers(player(PLAYER_1, 0, 500));
        DomainAuctionGateway gateway = new DomainAuctionGateway(store);

        int next = gateway.nextBidAmount(PLAYER_1, PROPERTY_ID, 0);

        assertTrue(next >= AuctionGateway.OPENING_BID);
        assertTrue(next <= 500);
        assertEquals(0, next % AuctionGateway.BID_INCREMENT, "bid must be multiple of BID_INCREMENT");
    }

    @Test
    void nextBidAmountReturnsZeroWhenCashBelowMinimum() {
        InMemorySessionState store = storeWithPlayers(player(PLAYER_1, 0, 5));
        DomainAuctionGateway gateway = new DomainAuctionGateway(store);

        int next = gateway.nextBidAmount(PLAYER_1, PROPERTY_ID, 0);

        assertEquals(0, next);
    }

    @Test
    void transferWinningPropertyDeductsCashAndRecordsOwnership() {
        InMemorySessionState store = storeWithPlayersAndProperties(
                List.of(player(PLAYER_1, 0, 500), player(PLAYER_2, 1, 300)),
                List.of(unownedProperty(PROPERTY_ID))
        );
        DomainAuctionGateway gateway = new DomainAuctionGateway(store);

        boolean result = gateway.transferWinningProperty(PLAYER_1, PROPERTY_ID, 100);

        assertTrue(result);
        SessionState updated = store.get();
        PlayerSnapshot winner = playerById(updated, PLAYER_1);
        assertEquals(400, winner.cash());
        assertTrue(winner.ownedPropertyIds().contains(PROPERTY_ID));

        PropertyStateSnapshot property = propertyById(updated, PROPERTY_ID);
        assertEquals(PLAYER_1, property.ownerPlayerId());
    }

    @Test
    void transferWinningPropertyDoesNotAffectOtherPlayers() {
        InMemorySessionState store = storeWithPlayersAndProperties(
                List.of(player(PLAYER_1, 0, 500), player(PLAYER_2, 1, 300)),
                List.of(unownedProperty(PROPERTY_ID))
        );
        DomainAuctionGateway gateway = new DomainAuctionGateway(store);

        gateway.transferWinningProperty(PLAYER_1, PROPERTY_ID, 100);

        assertEquals(300, playerById(store.get(), PLAYER_2).cash());
    }

    private static InMemorySessionState storeWithPlayers(PlayerSnapshot... players) {
        return storeWithPlayersAndProperties(List.of(players), List.of());
    }

    private static InMemorySessionState storeWithPlayersAndProperties(
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

    private static PlayerSnapshot player(String playerId, int seatIndex, int cash) {
        return new PlayerSnapshot(playerId, "seat-" + seatIndex, playerId, cash, 0, false, false, false, 0, 0, List.of());
    }

    private static PlayerSnapshot bankruptPlayer(String playerId, int seatIndex, int cash) {
        return new PlayerSnapshot(playerId, "seat-" + seatIndex, playerId, cash, 0, true, false, false, 0, 0, List.of());
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
