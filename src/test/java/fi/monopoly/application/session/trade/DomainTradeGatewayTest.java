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
