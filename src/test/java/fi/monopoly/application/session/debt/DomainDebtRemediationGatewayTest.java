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

    @Test
    void canSellRoundsReturnsFalseForZeroRounds() {
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 500), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 3, 0),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 3, 0)));
        assertFalse(new DomainDebtRemediationGateway(store).canSellBuildingRoundsAcrossSet("B1", 0, PLAYER_1));
    }

    @Test
    void canSellRoundsReturnsFalseForNonStreet() {
        // RR1 is a railroad, not a STREET placeType
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 500), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("RR1", PLAYER_1, false, 0, 0)));
        assertFalse(new DomainDebtRemediationGateway(store).canSellBuildingRoundsAcrossSet("RR1", 1, PLAYER_1));
    }

    @Test
    void canSellRoundsReturnsFalseWhenOnePropertyOwnedByOtherPlayer() {
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 500), player(PLAYER_2, 200),
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 2, 0),
                        new PropertyStateSnapshot("B2", PLAYER_2, false, 2, 0)));
        assertFalse(new DomainDebtRemediationGateway(store).canSellBuildingRoundsAcrossSet("B1", 1, PLAYER_1));
    }

    @Test
    void canSellRoundsAcceptsHotelAsLevel5() {
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 500), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 0, 1),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 0, 1)));
        assertTrue(new DomainDebtRemediationGateway(store).canSellBuildingRoundsAcrossSet("B1", 5, PLAYER_1));
        assertFalse(new DomainDebtRemediationGateway(store).canSellBuildingRoundsAcrossSet("B1", 6, PLAYER_1));
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

    @Test
    void sellBuildingRoundsDowngradeHotelToHouses() {
        // B1+B2 each have 1 hotel (level 5). Sell 1 round → level 4 = 4 houses.
        // Proceeds = 2 * (50/2) = 50
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 100), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 0, 1),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 0, 1)));
        new DomainDebtRemediationGateway(store).sellBuildingRoundsAcrossSet("B1", 1);

        PropertyStateSnapshot b1 = propertyById(store.get(), "B1");
        PropertyStateSnapshot b2 = propertyById(store.get(), "B2");
        assertEquals(4, b1.houseCount());
        assertEquals(0, b1.hotelCount());
        assertEquals(4, b2.houseCount());
        assertEquals(100 + 2 * (B1_HOUSE_PRICE / 2), playerById(store.get(), PLAYER_1).cash());
    }

    @Test
    void sellBuildingRoundsOnlyAffectsMatchingColorSet() {
        // B1+B2 (brown) each have 2 houses; LB1 (light-blue) has 3 houses.
        // Selling rounds on B1 must not touch LB1.
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 100), null,
                debt(PLAYER_1, null, 200),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 2, 0),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 2, 0),
                        new PropertyStateSnapshot("LB1", PLAYER_1, false, 3, 0)));
        new DomainDebtRemediationGateway(store).sellBuildingRoundsAcrossSet("B1", 2);

        assertEquals(0, propertyById(store.get(), "B1").houseCount());
        assertEquals(0, propertyById(store.get(), "B2").houseCount());
        assertEquals(3, propertyById(store.get(), "LB1").houseCount(), "LB1 must not be touched");
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
    void bankruptcyOfMiddleSeatPlayerPassesTurnToNextSeat() {
        // 3 players in seats 0,1,2 — the seat-1 player goes bankrupt on their own turn.
        // The turn must continue clockwise to seat 2, not jump back to seat 0.
        String player3 = "player-3";
        DebtStateModel debt = debt(PLAYER_2, null, 500);

        List<SeatState> seats = List.of(seat(PLAYER_1, 0), seat(PLAYER_2, 1), seat(player3, 2));
        PlayerSnapshot p1 = player(PLAYER_1, 500);
        PlayerSnapshot p2 = new PlayerSnapshot(PLAYER_2, "seat-1", PLAYER_2, 100, 0, false, false, false, 0, 0, List.of());
        PlayerSnapshot p3 = new PlayerSnapshot(player3, "seat-2", player3, 500, 0, false, false, false, 0, 0, List.of());
        SessionState state = new SessionState(SESSION_ID, 0L, SessionStatus.IN_PROGRESS,
                seats, List.of(p1, p2, p3), List.of(),
                new TurnState(PLAYER_2, TurnPhase.RESOLVING_DEBT, false, false, 0),
                null, null, debt, null, null);
        InMemorySessionState store = new InMemorySessionState(state);

        new DomainDebtRemediationGateway(store).declareBankruptcy();

        TurnState turn = store.get().turn();
        assertEquals(player3, turn.activePlayerId(),
                "the turn must pass to the seat after the bankrupt player, not restart from seat 0");
        assertEquals(TurnPhase.WAITING_FOR_ROLL, turn.phase());
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
    // Bank house supply must not be exceeded when a hotel is downgraded for debt
    // -------------------------------------------------------------------------

    @Test
    void sellingHotelForDebtDropsToZeroWhenBankCannotMakeChange() {
        // Board already holds 30 houses elsewhere; downgrading this hotel to 4 houses
        // would push the total to 34 > 32. The hotel must instead be sold straight to 0,
        // and the debtor is credited for all 5 building units.
        // 30 houses parked elsewhere: LB(4,4,4)=12 + P(4,4,4)=12 + O1(4)+O2(2)=6 → 30.
        List<PropertyStateSnapshot> props = List.of(
                new PropertyStateSnapshot("B1", PLAYER_1, false, 0, 1), // debtor's hotel
                new PropertyStateSnapshot("LB1", PLAYER_2, false, 4, 0),
                new PropertyStateSnapshot("LB2", PLAYER_2, false, 4, 0),
                new PropertyStateSnapshot("LB3", PLAYER_2, false, 4, 0),
                new PropertyStateSnapshot("P1", PLAYER_2, false, 4, 0),
                new PropertyStateSnapshot("P2", PLAYER_2, false, 4, 0),
                new PropertyStateSnapshot("P3", PLAYER_2, false, 4, 0),
                new PropertyStateSnapshot("O1", PLAYER_2, false, 4, 0),
                new PropertyStateSnapshot("O2", PLAYER_2, false, 2, 0));
        int housesElsewhere = props.stream().filter(p -> !p.propertyId().equals("B1"))
                .mapToInt(PropertyStateSnapshot::houseCount).sum();
        assertEquals(30, housesElsewhere, "test fixture must park exactly 30 houses elsewhere");

        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 0), player(PLAYER_2, 500),
                debt(PLAYER_1, null, 100), props);

        boolean sold = new DomainDebtRemediationGateway(store).sellBuildings("B1", 1);

        assertTrue(sold);
        PropertyStateSnapshot b1 = propertyById(store.get(), "B1");
        assertEquals(0, b1.houseCount(), "hotel must be sold to 0, not 4 houses");
        assertEquals(0, b1.hotelCount());
        int totalHouses = store.get().properties().stream()
                .mapToInt(PropertyStateSnapshot::houseCount).sum();
        assertTrue(totalHouses <= 32, "bank house supply must never be exceeded, got " + totalHouses);
        // Credited for all 5 units (hotel fully liquidated): 5 * (housePrice/2)
        assertEquals(5 * (B1_HOUSE_PRICE / 2), playerById(store.get(), PLAYER_1).cash());
    }

    @Test
    void sellingHotelForDebtBecomesFourHousesWhenBankHasRoom() {
        // Only 10 houses elsewhere: downgrading the hotel to 4 houses fits (14 ≤ 32).
        List<PropertyStateSnapshot> props = List.of(
                new PropertyStateSnapshot("B1", PLAYER_1, false, 0, 1),
                new PropertyStateSnapshot("LB1", PLAYER_2, false, 4, 0),
                new PropertyStateSnapshot("LB2", PLAYER_2, false, 4, 0),
                new PropertyStateSnapshot("LB3", PLAYER_2, false, 2, 0));
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 0), player(PLAYER_2, 500),
                debt(PLAYER_1, null, 100), props);

        new DomainDebtRemediationGateway(store).sellBuildings("B1", 1);

        assertEquals(4, propertyById(store.get(), "B1").houseCount(), "hotel downgrades to 4 houses normally");
        assertEquals(0, propertyById(store.get(), "B1").hotelCount());
        assertEquals(B1_HOUSE_PRICE / 2, playerById(store.get(), PLAYER_1).cash(), "credited for 1 unit only");
    }

    // -------------------------------------------------------------------------
    // estimatedLiquidationValue
    // -------------------------------------------------------------------------

    @Test
    void hotelLiquidatesAsFiveBuildingUnits() {
        // A hotel sells back one unit at a time (hotel → 4 houses → … → 0), each unit
        // returning housePrice/2 — so its liquidation value is 5 units, not 1.
        DebtStateModel debt = debt(PLAYER_1, null, 100);
        InMemorySessionState store = storeWithDebt(player(PLAYER_1, 0), null, debt,
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 0, 1)), null);

        int liquidation = DomainDebtRemediationGateway.estimatedLiquidationValue(store.get(), PLAYER_1);

        assertEquals(B1_MORTGAGE_VALUE + 5 * (B1_HOUSE_PRICE / 2), liquidation,
                "hotel must count as 5 building units in the liquidation estimate");
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
