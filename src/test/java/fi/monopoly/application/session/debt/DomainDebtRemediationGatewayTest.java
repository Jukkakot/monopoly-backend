package fi.monopoly.application.session.debt;

import fi.monopoly.application.session.InMemorySessionState;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.types.SpotType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DomainDebtRemediationGateway}.
 *
 * <p>Board properties used in tests (SpotType):
 * <pre>
 *  B1 (brown, price=60, housePrice=50)
 *  B2 (brown, price=60, housePrice=50)
 *  LB1 (light-blue, price=100)
 *  RR1 (railroad, price=200)
 * </pre></p>
 */
class DomainDebtRemediationGatewayTest {

    private static final String SESSION_ID = "test-session";
    private static final String PLAYER_1 = "player-1";
    private static final String PLAYER_2 = "player-2";

    private static final int B1_PRICE = SpotType.B1.getIntegerProperty("price");        // 60
    private static final int B1_HOUSE_PRICE = SpotType.B1.getIntegerProperty("housePrice"); // 50
    private static final int B1_MORTGAGE_VALUE = B1_PRICE / 2;                          // 30

    // -------------------------------------------------------------------------
    // payDebtNow — cash transfer
    // -------------------------------------------------------------------------

    @Test
    void payDebtNowDeductsFromDebtorWhenBankIsCreditor() {
        DebtStateModel debt = debt(PLAYER_1, null, 100);
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 300), null, debt, List.of(), null);
        new DomainDebtRemediationGateway(store).payDebtNow();

        assertEquals(200, playerById(store.get(), PLAYER_1).cash());
    }

    @Test
    void payDebtNowTransfersCashToCreditorPlayer() {
        DebtStateModel debt = debt(PLAYER_1, PLAYER_2, 150);
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 500), player(PLAYER_2, 200), debt, List.of(), null);
        new DomainDebtRemediationGateway(store).payDebtNow();

        assertEquals(350, playerById(store.get(), PLAYER_1).cash());
        assertEquals(350, playerById(store.get(), PLAYER_2).cash());
    }

    @Test
    void payDebtNowClearsActiveDebtFromStore() {
        DebtStateModel debt = debt(PLAYER_1, null, 100);
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 300), null, debt, List.of(), null);
        new DomainDebtRemediationGateway(store).payDebtNow();

        assertNull(store.get().activeDebt());
    }

    @Test
    void payDebtNowClearsTurnContinuationFromStore() {
        DebtStateModel debt = debt(PLAYER_1, null, 100);
        TurnContinuationState cont = continuation(PLAYER_1, TurnContinuationAction.END_TURN_WITH_SWITCH);
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 300), null, debt, List.of(), cont);
        new DomainDebtRemediationGateway(store).payDebtNow();

        assertNull(store.get().turnContinuationState());
    }

    // -------------------------------------------------------------------------
    // payDebtNow — turn phase after continuation
    // -------------------------------------------------------------------------

    @Test
    void payDebtNowWithEndTurnSwitchAdvancesToNextPlayer() {
        DebtStateModel debt = debt(PLAYER_1, null, 100);
        TurnContinuationState cont = continuation(PLAYER_1, TurnContinuationAction.END_TURN_WITH_SWITCH);
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 300), player(PLAYER_2, 300), debt, List.of(), cont);
        new DomainDebtRemediationGateway(store).payDebtNow();

        TurnState turn = store.get().turn();
        assertEquals(PLAYER_2, turn.activePlayerId());
        assertEquals(TurnPhase.WAITING_FOR_ROLL, turn.phase());
    }

    @Test
    void payDebtNowWithFollowUpKeepsSamePlayerAndDoubles() {
        DebtStateModel debt = debt(PLAYER_1, null, 100);
        TurnContinuationState cont = continuation(PLAYER_1, TurnContinuationAction.APPLY_TURN_FOLLOW_UP);
        InMemorySessionState store = storeWithDebtAndDoubles(player(PLAYER_1, 300), null, debt, cont, 2);
        new DomainDebtRemediationGateway(store).payDebtNow();

        TurnState turn = store.get().turn();
        assertEquals(PLAYER_1, turn.activePlayerId());
        assertEquals(TurnPhase.WAITING_FOR_ROLL, turn.phase());
        assertEquals(2, turn.consecutiveDoubles());
    }

    @Test
    void payDebtNowWithNoContinuationSetsWaitingForEndTurn() {
        DebtStateModel debt = debt(PLAYER_1, null, 100);
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 300), null, debt, List.of(), null);
        new DomainDebtRemediationGateway(store).payDebtNow();

        assertEquals(TurnPhase.WAITING_FOR_END_TURN, store.get().turn().phase());
    }

    // -------------------------------------------------------------------------
    // canMortgage
    // -------------------------------------------------------------------------

    @Test
    void canMortgageReturnsTrueForUnmortgagedOwnedStreetWithNoBuildings() {
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 500), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 0, 0),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 0, 0)));
        assertTrue(new DomainDebtRemediationGateway(store).canMortgage("B1", PLAYER_1));
    }

    @Test
    void canMortgageReturnsFalseIfAlreadyMortgaged() {
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 500), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, true, 0, 0)));
        assertFalse(new DomainDebtRemediationGateway(store).canMortgage("B1", PLAYER_1));
    }

    @Test
    void canMortgageReturnsFalseIfBuildingsExistInSet() {
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 500), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 2, 0),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 0, 0)));
        assertFalse(new DomainDebtRemediationGateway(store).canMortgage("B2", PLAYER_1));
    }

    @Test
    void canMortgageReturnsFalseIfWrongOwner() {
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 500), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_2, false, 0, 0)));
        assertFalse(new DomainDebtRemediationGateway(store).canMortgage("B1", PLAYER_1));
    }

    // -------------------------------------------------------------------------
    // mortgageProperty
    // -------------------------------------------------------------------------

    @Test
    void mortgagePropertyMarksMortgagedAndAddsCash() {
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 200), null,
                debt(PLAYER_1, null, 400),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 0, 0)));
        new DomainDebtRemediationGateway(store).mortgageProperty("B1");

        assertTrue(propertyById(store.get(), "B1").mortgaged());
        assertEquals(200 + B1_MORTGAGE_VALUE, playerById(store.get(), PLAYER_1).cash());
    }

    @Test
    void mortgagePropertyReturnsFalseIfAlreadyMortgaged() {
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 200), null,
                debt(PLAYER_1, null, 400),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, true, 0, 0)));
        assertFalse(new DomainDebtRemediationGateway(store).mortgageProperty("B1"));
    }

    // -------------------------------------------------------------------------
    // canSellBuildings
    // -------------------------------------------------------------------------

    @Test
    void canSellBuildingsReturnsTrueWhenEvenSellAllowed() {
        // B1 has 3 houses, B2 has 3 houses → selling 1 from B1: result=2, maxRest=3, 2 >= 3-1=2 ✓
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 500), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 3, 0),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 3, 0)));
        assertTrue(new DomainDebtRemediationGateway(store).canSellBuildings("B1", 1, PLAYER_1));
    }

    @Test
    void canSellBuildingsReturnsFalseWhenEvenSellViolated() {
        // B1 has 2 houses, B2 has 3 → selling 1 from B1: result=1, maxRest=3, 1 >= 3-1=2? ✗
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 500), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 2, 0),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 3, 0)));
        assertFalse(new DomainDebtRemediationGateway(store).canSellBuildings("B1", 1, PLAYER_1));
    }

    @Test
    void canSellBuildingsReturnsFalseIfNotEnoughBuildings() {
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 500), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 1, 0),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 1, 0)));
        assertFalse(new DomainDebtRemediationGateway(store).canSellBuildings("B1", 2, PLAYER_1));
    }

    // -------------------------------------------------------------------------
    // sellBuildings
    // -------------------------------------------------------------------------

    @Test
    void sellBuildingsReducesHousesAndAddsSaleValue() {
        // B1 has 2 houses. Sell 1: proceeds = 1 * (50/2) = 25
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 100), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 2, 0),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 2, 0)));
        new DomainDebtRemediationGateway(store).sellBuildings("B1", 1);

        assertEquals(1, propertyById(store.get(), "B1").houseCount());
        assertEquals(100 + B1_HOUSE_PRICE / 2, playerById(store.get(), PLAYER_1).cash());
    }

    @Test
    void sellBuildingsConvertsHotelToHouses() {
        // Hotel = level 5. Sell 1 → level 4 = 4 houses
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 100), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 0, 1),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 0, 1)));
        new DomainDebtRemediationGateway(store).sellBuildings("B1", 1);

        PropertyStateSnapshot b1 = propertyById(store.get(), "B1");
        assertEquals(4, b1.houseCount());
        assertEquals(0, b1.hotelCount());
    }

    // -------------------------------------------------------------------------
    // canSellBuildingRoundsAcrossSet
    // -------------------------------------------------------------------------

    @Test
    void canSellRoundsReturnsTrueWhenAllHaveEnoughBuildings() {
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 500), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 2, 0),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 2, 0)));
        assertTrue(new DomainDebtRemediationGateway(store).canSellBuildingRoundsAcrossSet("B1", 2, PLAYER_1));
    }

    @Test
    void canSellRoundsReturnsFalseIfOnePropertyHasTooFew() {
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 500), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 1, 0),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 3, 0)));
        assertFalse(new DomainDebtRemediationGateway(store).canSellBuildingRoundsAcrossSet("B1", 2, PLAYER_1));
    }

    // -------------------------------------------------------------------------
    // sellBuildingRoundsAcrossSet
    // -------------------------------------------------------------------------

    @Test
    void sellBuildingRoundsReducesAllPropertiesInSetAndAddsCash() {
        // B1 and B2 each have 3 houses. Sell 1 round → each loses 1. Proceeds = 2 * (50/2) = 50
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 100), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 3, 0),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 3, 0)));
        new DomainDebtRemediationGateway(store).sellBuildingRoundsAcrossSet("B1", 1);

        assertEquals(2, propertyById(store.get(), "B1").houseCount());
        assertEquals(2, propertyById(store.get(), "B2").houseCount());
        assertEquals(100 + 2 * (B1_HOUSE_PRICE / 2), playerById(store.get(), PLAYER_1).cash());
    }

    // -------------------------------------------------------------------------
    // declareBankruptcy — player elimination
    // -------------------------------------------------------------------------

    @Test
    void declareBankruptcyEliminatesDebtorPlayer() {
        DebtStateModel debt = debt(PLAYER_1, null, 500);
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 100), null, debt, null);
        new DomainDebtRemediationGateway(store).declareBankruptcy();

        PlayerSnapshot debtor = playerById(store.get(), PLAYER_1);
        assertTrue(debtor.eliminated());
        assertTrue(debtor.bankrupt());
        assertEquals(0, debtor.cash());
    }

    @Test
    void declareBankruptcyTransfersPropertiesToCreditorPlayer() {
        DebtStateModel debt = debt(PLAYER_1, PLAYER_2, 500);
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 100), player(PLAYER_2, 300), debt,
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 0, 0)));
        new DomainDebtRemediationGateway(store).declareBankruptcy();

        PropertyStateSnapshot b1 = propertyById(store.get(), "B1");
        assertEquals(PLAYER_2, b1.ownerPlayerId());
        assertFalse(b1.mortgaged()); // buildings reset
    }

    @Test
    void declareBankruptcyTowardsBankMakesPropertiesUnowned() {
        DebtStateModel debt = debt(PLAYER_1, null, 500);
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 100), null, debt,
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 2, 0)));
        new DomainDebtRemediationGateway(store).declareBankruptcy();

        PropertyStateSnapshot b1 = propertyById(store.get(), "B1");
        assertNull(b1.ownerPlayerId());
        assertEquals(0, b1.houseCount()); // buildings cleared
    }

    @Test
    void declareBankruptcyTransfersCashToCreditor() {
        DebtStateModel debt = debt(PLAYER_1, PLAYER_2, 500);
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 100), player(PLAYER_2, 300), debt, null);
        new DomainDebtRemediationGateway(store).declareBankruptcy();

        assertEquals(400, playerById(store.get(), PLAYER_2).cash());
    }

    @Test
    void declareBankruptcyAdvancesToNextActivePlayer() {
        DebtStateModel debt = debt(PLAYER_1, null, 500);
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 100), player(PLAYER_2, 300), debt, null);
        new DomainDebtRemediationGateway(store).declareBankruptcy();

        TurnState turn = store.get().turn();
        assertEquals(PLAYER_2, turn.activePlayerId());
        assertEquals(TurnPhase.WAITING_FOR_ROLL, turn.phase());
    }

    @Test
    void declareBankruptcySetsClearsActiveDebt() {
        DebtStateModel debt = debt(PLAYER_1, null, 500);
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 100), null, debt, null);
        new DomainDebtRemediationGateway(store).declareBankruptcy();

        assertNull(store.get().activeDebt());
    }

    @Test
    void declareBankruptcySetsWinnerWhenOnlyOnePlayerLeft() {
        DebtStateModel debt = debt(PLAYER_1, PLAYER_2, 500);
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 100), player(PLAYER_2, 300), debt, null);
        new DomainDebtRemediationGateway(store).declareBankruptcy();

        assertEquals(PLAYER_2, store.get().winnerPlayerId());
        assertEquals(SessionStatus.GAME_OVER, store.get().status());
    }

    @Test
    void declareBankruptcyNoWinnerWithMultiplePlayersRemaining() {
        // 3 players: p1 goes bankrupt, p2+p3 remain
        DebtStateModel debt = debt(PLAYER_1, null, 500);
        String player3 = "player-3";

        List<SeatState> seats = List.of(
                seat(PLAYER_1, 0), seat(PLAYER_2, 1),
                new SeatState("seat-2", 2, player3, SeatKind.HUMAN, ControlMode.MANUAL, player3, "HUMAN", "#FF0000")
        );
        PlayerSnapshot p3 = new PlayerSnapshot(player3, "seat-2", player3, 500, 0, false, false, false, 0, 0, List.of());
        SessionState state = new SessionState(SESSION_ID, 0L, SessionStatus.IN_PROGRESS,
                seats, List.of(player(PLAYER_1, 100), player(PLAYER_2, 300), p3), List.of(),
                new TurnState(PLAYER_1, TurnPhase.RESOLVING_DEBT, false, false, 0),
                null, null, debt, null, null);
        InMemorySessionState store = new InMemorySessionState(state);

        new DomainDebtRemediationGateway(store).declareBankruptcy();

        assertNull(store.get().winnerPlayerId());
        assertEquals(SessionStatus.IN_PROGRESS, store.get().status());
    }

    // -------------------------------------------------------------------------
    // Helpers: store builders
    // -------------------------------------------------------------------------

    private static InMemorySessionState storeWithProps(PlayerSnapshot p1, PlayerSnapshot p2,
                                                        DebtStateModel debt,
                                                        List<PropertyStateSnapshot> props) {
        return storeWithDebt(p1, p2, debt, props, null);
    }

    private static InMemorySessionState storeWithCont(PlayerSnapshot p1, PlayerSnapshot p2,
                                                       DebtStateModel debt,
                                                       TurnContinuationState cont) {
        return storeWithDebt(p1, p2, debt, List.of(), cont);
    }

    private static InMemorySessionState storeWithDebt(PlayerSnapshot p1, PlayerSnapshot p2,
                                                       DebtStateModel debt,
                                                       List<PropertyStateSnapshot> props) {
        return storeWithDebt(p1, p2, debt, props != null ? props : List.of(), null);
    }

    private static InMemorySessionState storeWithDebt(PlayerSnapshot p1, PlayerSnapshot p2,
                                                       DebtStateModel debt,
                                                       List<PropertyStateSnapshot> props,
                                                       TurnContinuationState cont) {
        List<SeatState> seats = p2 != null
                ? List.of(seat(PLAYER_1, 0), seat(PLAYER_2, 1))
                : List.of(seat(PLAYER_1, 0));
        List<PlayerSnapshot> players = p2 != null ? List.of(p1, p2) : List.of(p1);
        SessionState state = new SessionState(SESSION_ID, 0L, SessionStatus.IN_PROGRESS,
                seats, players, props,
                new TurnState(p1.playerId(), TurnPhase.RESOLVING_DEBT, false, false, 0),
                null, null, debt, null, cont, null);
        return new InMemorySessionState(state);
    }

    private static InMemorySessionState storeWithDebtAndDoubles(PlayerSnapshot p1, PlayerSnapshot p2,
                                                                  DebtStateModel debt,
                                                                  TurnContinuationState cont,
                                                                  int consecutiveDoubles) {
        List<SeatState> seats = p2 != null
                ? List.of(seat(PLAYER_1, 0), seat(PLAYER_2, 1))
                : List.of(seat(PLAYER_1, 0));
        List<PlayerSnapshot> players = p2 != null ? List.of(p1, p2) : List.of(p1);
        SessionState state = new SessionState(SESSION_ID, 0L, SessionStatus.IN_PROGRESS,
                seats, players, List.of(),
                new TurnState(p1.playerId(), TurnPhase.RESOLVING_DEBT, false, false, consecutiveDoubles),
                null, null, debt, null, cont, null);
        return new InMemorySessionState(state);
    }

    // -------------------------------------------------------------------------
    // Helpers: domain object factories
    // -------------------------------------------------------------------------

    private static PlayerSnapshot player(String playerId, int cash) {
        int seatIdx = PLAYER_1.equals(playerId) ? 0 : 1;
        return new PlayerSnapshot(playerId, "seat-" + seatIdx, playerId,
                cash, 0, false, false, false, 0, 0, List.of());
    }

    private static DebtStateModel debt(String debtorId, String creditorId, int amount) {
        return new DebtStateModel(
                "debt-1", debtorId,
                creditorId != null ? DebtCreditorType.PLAYER : DebtCreditorType.BANK,
                creditorId, amount, "test-reason", false, 0, 0,
                List.of(DebtAction.PAY_DEBT_NOW)
        );
    }

    private static TurnContinuationState continuation(String playerId, TurnContinuationAction action) {
        return new TurnContinuationState("cont-1", playerId,
                TurnContinuationType.RESUME_AFTER_DEBT, action, null, null);
    }

    private static SeatState seat(String playerId, int index) {
        return new SeatState("seat-" + index, index, playerId,
                SeatKind.HUMAN, ControlMode.MANUAL, playerId, "HUMAN", "#000000");
    }

    private static PlayerSnapshot playerById(SessionState state, String playerId) {
        return state.players().stream()
                .filter(p -> playerId.equals(p.playerId()))
                .findFirst().orElseThrow();
    }

    private static PropertyStateSnapshot propertyById(SessionState state, String propertyId) {
        return state.properties().stream()
                .filter(p -> propertyId.equals(p.propertyId()))
                .findFirst().orElseThrow();
    }
}
