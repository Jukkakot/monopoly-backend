package fi.monopoly.application.session.trade;

import fi.monopoly.application.session.InMemorySessionState;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DomainTradeGatewayTest {

    private static final String SESSION_ID = "test-session";
    private static final String P1 = "player-1";
    private static final String P2 = "player-2";

    // -------------------------------------------------------------------------
    // playerExists
    // -------------------------------------------------------------------------

    @Test
    void playerExistsReturnsTrueForActivePlayer() {
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 500), List.of());
        assertTrue(new DomainTradeGateway(store).playerExists(P1));
    }

    @Test
    void playerExistsReturnsFalseForEliminatedPlayer() {
        PlayerSnapshot eliminated = eliminatedPlayer(P1);
        InMemorySessionState store = storeWithPlayers(eliminated, player(P2, 500), List.of());
        assertFalse(new DomainTradeGateway(store).playerExists(P1));
    }

    @Test
    void playerExistsReturnsFalseForUnknownId() {
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 500), List.of());
        assertFalse(new DomainTradeGateway(store).playerExists("nobody"));
    }

    // -------------------------------------------------------------------------
    // isValidOffer — basic checks
    // -------------------------------------------------------------------------

    @Test
    void isValidReturnsFalseForEmptyOffer() {
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 500), List.of());
        TradeOfferState offer = new TradeOfferState(P1, P2, TradeSelectionState.NONE, TradeSelectionState.NONE);
        assertFalse(new DomainTradeGateway(store).isValidOffer(offer));
    }

    @Test
    void isValidReturnsFalseWhenSamePlayer() {
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 500), List.of());
        TradeOfferState offer = new TradeOfferState(P1, P1, cash(100), TradeSelectionState.NONE);
        assertFalse(new DomainTradeGateway(store).isValidOffer(offer));
    }

    @Test
    void isValidReturnsTrueForCashOnlyOffer() {
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 500), List.of());
        TradeOfferState offer = new TradeOfferState(P1, P2, cash(200), TradeSelectionState.NONE);
        assertTrue(new DomainTradeGateway(store).isValidOffer(offer));
    }

    @Test
    void isValidReturnsFalseIfProposerHasInsufficientCash() {
        InMemorySessionState store = storeWithPlayers(player(P1, 100), player(P2, 500), List.of());
        TradeOfferState offer = new TradeOfferState(P1, P2, cash(200), TradeSelectionState.NONE);
        assertFalse(new DomainTradeGateway(store).isValidOffer(offer));
    }

    @Test
    void isValidReturnsFalseIfRecipientHasInsufficientCash() {
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 50), List.of());
        TradeOfferState offer = new TradeOfferState(P1, P2, TradeSelectionState.NONE, cash(100));
        assertFalse(new DomainTradeGateway(store).isValidOffer(offer));
    }

    // -------------------------------------------------------------------------
    // isValidOffer — property checks
    // -------------------------------------------------------------------------

    @Test
    void isValidReturnsTrueForPropertyTrade() {
        PropertyStateSnapshot b1 = prop("B1", P1, false, 0, 0);
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 500), List.of(b1));
        TradeOfferState offer = new TradeOfferState(P1, P2, properties(List.of("B1")), TradeSelectionState.NONE);
        assertTrue(new DomainTradeGateway(store).isValidOffer(offer));
    }

    @Test
    void isValidReturnsFalseIfPropertyNotOwnedByProposer() {
        PropertyStateSnapshot b1 = prop("B1", P2, false, 0, 0);
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 500), List.of(b1));
        TradeOfferState offer = new TradeOfferState(P1, P2, properties(List.of("B1")), TradeSelectionState.NONE);
        assertFalse(new DomainTradeGateway(store).isValidOffer(offer));
    }

    @Test
    void isValidReturnsFalseIfPropertyHasBuildings() {
        PropertyStateSnapshot b1 = prop("B1", P1, false, 2, 0);
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 500), List.of(b1));
        TradeOfferState offer = new TradeOfferState(P1, P2, properties(List.of("B1")), TradeSelectionState.NONE);
        assertFalse(new DomainTradeGateway(store).isValidOffer(offer));
    }

    @Test
    void isValidReturnsFalseIfPropertyHasHotel() {
        PropertyStateSnapshot b1 = prop("B1", P1, false, 0, 1);
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 500), List.of(b1));
        TradeOfferState offer = new TradeOfferState(P1, P2, properties(List.of("B1")), TradeSelectionState.NONE);
        assertFalse(new DomainTradeGateway(store).isValidOffer(offer));
    }

    @Test
    void isValidReturnsFalseWhenTradingZeroHouseSiblingWhileColorGroupHasBuildings() {
        // Even-building can leave a group at (0,1): B2 holds a house, B1 has none.
        // Trading the 0-house B1 must be rejected — all buildings in the color group
        // must be sold first (mirrors the mortgage rule). Otherwise the monopoly splits
        // while a building stays stranded on B2, an invalid game state.
        PropertyStateSnapshot b1 = prop("B1", P1, false, 0, 0);
        PropertyStateSnapshot b2 = prop("B2", P1, false, 1, 0);
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 500), List.of(b1, b2));
        TradeOfferState offer = new TradeOfferState(P1, P2, properties(List.of("B1")), TradeSelectionState.NONE);
        assertFalse(new DomainTradeGateway(store).isValidOffer(offer));
    }

    @Test
    void isValidReturnsTrueTradingSiblingWhenColorGroupHasNoBuildings() {
        // Guard against over-blocking: a building-free group must still be tradeable
        // even with the sibling present in state.
        PropertyStateSnapshot b1 = prop("B1", P1, false, 0, 0);
        PropertyStateSnapshot b2 = prop("B2", P1, false, 0, 0);
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 500), List.of(b1, b2));
        TradeOfferState offer = new TradeOfferState(P1, P2, properties(List.of("B1")), TradeSelectionState.NONE);
        assertTrue(new DomainTradeGateway(store).isValidOffer(offer));
    }

    // -------------------------------------------------------------------------
    // isValidOffer — jail card checks
    // -------------------------------------------------------------------------

    @Test
    void isValidReturnsTrueWhenProposerOffersJailCard() {
        InMemorySessionState store = storeWithPlayers(playerWithJailCard(P1, 500, 1), player(P2, 500), List.of());
        TradeOfferState offer = new TradeOfferState(P1, P2, jailCard(), TradeSelectionState.NONE);
        assertTrue(new DomainTradeGateway(store).isValidOffer(offer));
    }

    @Test
    void isValidReturnsFalseWhenProposerHasNoJailCard() {
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 500), List.of());
        TradeOfferState offer = new TradeOfferState(P1, P2, jailCard(), TradeSelectionState.NONE);
        assertFalse(new DomainTradeGateway(store).isValidOffer(offer));
    }

    // -------------------------------------------------------------------------
    // applyOffer — cash transfer
    // -------------------------------------------------------------------------

    @Test
    void applyOfferTransfersCashFromProposerToRecipient() {
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 200), List.of());
        TradeOfferState offer = new TradeOfferState(P1, P2, cash(300), TradeSelectionState.NONE);
        assertTrue(new DomainTradeGateway(store).applyOffer(offer));

        assertEquals(200, playerById(store.get(), P1).cash());
        assertEquals(500, playerById(store.get(), P2).cash());
    }

    @Test
    void applyOfferTransfersCashBothDirections() {
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 300), List.of());
        // P1 offers €200 cash, requests €100 cash from P2
        TradeOfferState offer = new TradeOfferState(P1, P2, cash(200), cash(100));
        new DomainTradeGateway(store).applyOffer(offer);

        // P1: 500 - 200 + 100 = 400
        // P2: 300 + 200 - 100 = 400
        assertEquals(400, playerById(store.get(), P1).cash());
        assertEquals(400, playerById(store.get(), P2).cash());
    }

    // -------------------------------------------------------------------------
    // applyOffer — property transfer
    // -------------------------------------------------------------------------

    @Test
    void applyOfferTransfersPropertyFromProposerToRecipient() {
        PropertyStateSnapshot b1 = prop("B1", P1, false, 0, 0);
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 500), List.of(b1));
        TradeOfferState offer = new TradeOfferState(P1, P2, properties(List.of("B1")), TradeSelectionState.NONE);
        new DomainTradeGateway(store).applyOffer(offer);

        assertEquals(P2, propertyById(store.get(), "B1").ownerPlayerId());
    }

    @Test
    void applyOfferTransfersPropertyBothDirections() {
        PropertyStateSnapshot b1 = prop("B1", P1, false, 0, 0);
        PropertyStateSnapshot lb1 = prop("LB1", P2, false, 0, 0);
        InMemorySessionState store = storeWithPlayers(player(P1, 500), player(P2, 500), List.of(b1, lb1));
        TradeOfferState offer = new TradeOfferState(P1, P2, properties(List.of("B1")), properties(List.of("LB1")));
        new DomainTradeGateway(store).applyOffer(offer);

        assertEquals(P2, propertyById(store.get(), "B1").ownerPlayerId());
        assertEquals(P1, propertyById(store.get(), "LB1").ownerPlayerId());
    }

    @Test
    void pureSwapUpdatesPlayersOwnedPropertyLists() {
        // A property-for-property swap has zero cash and jail-card deltas — the players'
        // ownedPropertyIds must still be rebuilt (the client's player list, net worth and
        // game-over rankings all read them).
        PropertyStateSnapshot b1 = prop("B1", P1, false, 0, 0);
        PropertyStateSnapshot lb1 = prop("LB1", P2, false, 0, 0);
        PlayerSnapshot p1 = new PlayerSnapshot(P1, "seat-0", P1, 500, 0, false, false, false, 0, 0, List.of("B1"));
        PlayerSnapshot p2 = new PlayerSnapshot(P2, "seat-1", P2, 500, 0, false, false, false, 0, 0, List.of("LB1"));
        InMemorySessionState store = storeWithPlayers(p1, p2, List.of(b1, lb1));

        new DomainTradeGateway(store).applyOffer(
                new TradeOfferState(P1, P2, properties(List.of("B1")), properties(List.of("LB1"))));

        assertEquals(List.of("LB1"), playerById(store.get(), P1).ownedPropertyIds(),
                "proposer's owned list must reflect the swap");
        assertEquals(List.of("B1"), playerById(store.get(), P2).ownedPropertyIds(),
                "recipient's owned list must reflect the swap");
    }

    // -------------------------------------------------------------------------
    // applyOffer — jail card transfer
    // -------------------------------------------------------------------------

    @Test
    void applyOfferTransfersJailCard() {
        InMemorySessionState store = storeWithPlayers(playerWithJailCard(P1, 500, 1), player(P2, 500), List.of());
        TradeOfferState offer = new TradeOfferState(P1, P2, jailCard(), TradeSelectionState.NONE);
        new DomainTradeGateway(store).applyOffer(offer);

        assertEquals(0, playerById(store.get(), P1).getOutOfJailCards());
        assertEquals(1, playerById(store.get(), P2).getOutOfJailCards());
    }

    @Test
    void applyOfferReturnsFalseForInvalidOffer() {
        InMemorySessionState store = storeWithPlayers(player(P1, 50), player(P2, 500), List.of());
        TradeOfferState offer = new TradeOfferState(P1, P2, cash(200), TradeSelectionState.NONE);
        assertFalse(new DomainTradeGateway(store).applyOffer(offer));
        // State unchanged
        assertEquals(50, playerById(store.get(), P1).cash());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static InMemorySessionState storeWithPlayers(PlayerSnapshot p1, PlayerSnapshot p2,
                                                          List<PropertyStateSnapshot> props) {
        List<SeatState> seats = List.of(seat(P1, 0), seat(P2, 1));
        SessionState state = new SessionState(SESSION_ID, 0L, SessionStatus.IN_PROGRESS,
                seats, List.of(p1, p2), props,
                new TurnState(P1, TurnPhase.WAITING_FOR_ROLL, false, false, 0),
                null, null, null, null, null, null);
        return new InMemorySessionState(state);
    }

    private static PlayerSnapshot player(String id, int cash) {
        int idx = P1.equals(id) ? 0 : 1;
        return new PlayerSnapshot(id, "seat-" + idx, id, cash, 0, false, false, false, 0, 0, List.of());
    }

    private static PlayerSnapshot playerWithJailCard(String id, int cash, int jailCards) {
        int idx = P1.equals(id) ? 0 : 1;
        return new PlayerSnapshot(id, "seat-" + idx, id, cash, 0, false, false, false, 0, jailCards, List.of());
    }

    private static PlayerSnapshot eliminatedPlayer(String id) {
        int idx = P1.equals(id) ? 0 : 1;
        return new PlayerSnapshot(id, "seat-" + idx, id, 0, 0, true, true, false, 0, 0, List.of());
    }

    private static PropertyStateSnapshot prop(String id, String owner, boolean mortgaged, int houses, int hotels) {
        return new PropertyStateSnapshot(id, owner, mortgaged, houses, hotels);
    }

    private static SeatState seat(String playerId, int index) {
        return new SeatState("seat-" + index, index, playerId,
                SeatKind.HUMAN, ControlMode.MANUAL, playerId, "HUMAN", "#000000");
    }

    private static TradeSelectionState cash(int amount) {
        return new TradeSelectionState(amount, List.of(), 0);
    }

    private static TradeSelectionState properties(List<String> ids) {
        return new TradeSelectionState(0, ids, 0);
    }

    private static TradeSelectionState jailCard() {
        return new TradeSelectionState(0, List.of(), 1);
    }

    private static PlayerSnapshot playerById(SessionState state, String playerId) {
        return state.players().stream().filter(p -> playerId.equals(p.playerId())).findFirst().orElseThrow();
    }

    private static PropertyStateSnapshot propertyById(SessionState state, String propertyId) {
        return state.properties().stream().filter(p -> propertyId.equals(p.propertyId())).findFirst().orElseThrow();
    }
}
