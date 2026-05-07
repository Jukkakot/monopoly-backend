package fi.monopoly.application.session.turn;

import fi.monopoly.application.session.InMemorySessionState;
import fi.monopoly.application.session.purchase.PropertyPurchaseFlow;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.types.SpotType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DomainTurnActionGateway} using injected fixed dice values.
 *
 * <p>Board positions used in tests (SpotType.SPOT_TYPES index):
 * <pre>
 *  0=GO   1=B1   2=COMMUNITY1  3=B2   4=TAX1   5=RR1  ...
 * 10=JAIL  ...  30=GO_TO_JAIL  ...  37=DB1  38=TAX2  39=DB2
 * </pre></p>
 */
class DomainTurnActionGatewayTest {

    private static final String SESSION_ID = "test-session";
    private static final String PLAYER_1 = "player-1";
    private static final String PLAYER_2 = "player-2";

    // Board index constants (matches SpotType.SPOT_TYPES order)
    private static final int GO_INDEX = 0;   // GO_SPOT
    private static final int B1_INDEX = 1;   // B1, price=60
    private static final int B2_INDEX = 3;   // B2, price=60
    private static final int TAX1_INDEX = 4; // income tax €200
    private static final int JAIL_INDEX = 10;
    private static final int GO_TO_JAIL_INDEX = 30;
    private static final int TAX2_INDEX = 38; // luxury tax €100
    private static final int DB2_INDEX = 39;  // last spot, price=400

    // -------------------------------------------------------------------------
    // rollDice — normal movement
    // -------------------------------------------------------------------------

    @Test
    void rollDiceMovesPlayerByDiceSum() {
        InMemorySessionState store = storeWith(player(PLAYER_1, GO_INDEX, 1500));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1); // sum=3, not doubles

        assertTrue(gateway.rollDice());

        PlayerSnapshot updated = playerById(store.get(), PLAYER_1);
        assertEquals(B2_INDEX, updated.boardIndex()); // GO + 3 = index 3 = B2
    }

    @Test
    void rollDiceSetsWaitingForEndTurnOnNonPropertySpot() {
        // Player at index 7 (CHANCE1), move 3 → index 10 = JAIL (visiting corner → end turn)
        InMemorySessionState store = storeWith(player(PLAYER_1, 7, 1500));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1); // sum=3, not doubles

        gateway.rollDice();

        assertEquals(TurnPhase.WAITING_FOR_END_TURN, store.get().turn().phase());
    }

    @Test
    void rollDiceSetsWaitingForRollOnDoubles() {
        InMemorySessionState store = storeWith(player(PLAYER_1, GO_INDEX, 1500));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 2); // doubles

        gateway.rollDice();

        assertEquals(TurnPhase.WAITING_FOR_ROLL, store.get().turn().phase());
    }

    @Test
    void rollDiceIncrementsConsecutiveDoublesOnDoubles() {
        InMemorySessionState store = storeWith(player(PLAYER_1, GO_INDEX, 1500));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 3, 3); // doubles

        gateway.rollDice();

        assertEquals(1, store.get().turn().consecutiveDoubles());
    }

    @Test
    void rollDiceResetsConsecutiveDoublesOnNoDoubles() {
        InMemorySessionState store = storeWithTurn(
                player(PLAYER_1, GO_INDEX, 1500),
                new TurnState(PLAYER_1, TurnPhase.WAITING_FOR_ROLL, true, false, 1)
        );
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1); // not doubles

        gateway.rollDice();

        assertEquals(0, store.get().turn().consecutiveDoubles());
    }

    // -------------------------------------------------------------------------
    // rollDice — GO bonus
    // -------------------------------------------------------------------------

    @Test
    void rollDiceAwardsGoBonusWhenPassingGo() {
        // Player at index 37 (DB1), move 4 → 37+4=41, 41%40=1, passedGo=true
        InMemorySessionState store = storeWith(player(PLAYER_1, 37, 1500));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 2); // sum=4, doubles

        gateway.rollDice();

        // Cash should include GO bonus (€200) regardless of landing spot
        int cash = playerById(store.get(), PLAYER_1).cash();
        assertEquals(1500 + 200, cash);
    }

    @Test
    void rollDiceNoGoBonusWhenNotPassingGo() {
        // Player at index 0, move 3 → index 3 (B2, unowned)
        InMemorySessionState store = storeWith(player(PLAYER_1, GO_INDEX, 1500));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1); // not doubles, no purchase

        gateway.rollDice();

        // No property purchase triggered, no cash change from that; just no GO bonus
        // Cash may stay 1500 (no rent/tax) or decrease (if landed on property with owner)
        // B2 is unowned here, so decision opens but no cash change yet
        int cash = playerById(store.get(), PLAYER_1).cash();
        assertEquals(1500, cash);
    }

    // -------------------------------------------------------------------------
    // rollDice — GO_TO_JAIL
    // -------------------------------------------------------------------------

    @Test
    void rollDiceSendsPlayerToJailWhenLandingOnGoToJail() {
        // Player at index 27 (Y2), move 3 → 27+3=30 = GO_TO_JAIL
        InMemorySessionState store = storeWith(player(PLAYER_1, 27, 1500));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1); // sum=3

        gateway.rollDice();

        PlayerSnapshot updated = playerById(store.get(), PLAYER_1);
        assertTrue(updated.inJail());
        assertEquals(JAIL_INDEX, updated.boardIndex());
        assertEquals(3, updated.jailRoundsRemaining());
    }

    @Test
    void rollDiceSendsToJailOnThirdConsecutiveDoubles() {
        InMemorySessionState store = storeWithTurn(
                player(PLAYER_1, GO_INDEX, 1500),
                new TurnState(PLAYER_1, TurnPhase.WAITING_FOR_ROLL, true, false, 2)
        );
        DomainTurnActionGateway gateway = gatewayWithDice(store, 3, 3); // doubles → 3rd consecutive

        gateway.rollDice();

        PlayerSnapshot updated = playerById(store.get(), PLAYER_1);
        assertTrue(updated.inJail());
        assertEquals(JAIL_INDEX, updated.boardIndex());
    }

    // -------------------------------------------------------------------------
    // rollDice — property purchase decision
    // -------------------------------------------------------------------------

    @Test
    void rollDiceOpensPropertyDecisionOnUnownedProperty() {
        // Player at index 0, move 3 → B2 (index 3, unowned)
        AtomicReference<String> openedPropertyId = new AtomicReference<>();
        InMemorySessionState store = storeWith(player(PLAYER_1, GO_INDEX, 1500));
        PropertyPurchaseFlow flow = (playerId, propertyId, displayName, price, message, continuation) ->
                openedPropertyId.set(propertyId);
        DomainTurnActionGateway gateway = gatewayWithDice(store, flow, 2, 1);

        gateway.rollDice();

        assertEquals("B2", openedPropertyId.get());
        assertEquals(TurnPhase.WAITING_FOR_DECISION, store.get().turn().phase());
    }

    @Test
    void rollDiceSetsCorrectContinuationActionOnDoubles() {
        // Player at index 0, doubles (2,2)=4 → COMMUNITY1 (index 4... wait that's TAX1)
        // Actually index 0 + 4 = 4 = TAX1. Let me use (3,3)=6 → index 6 = LB1 (unowned)
        AtomicReference<TurnContinuationState> capturedContinuation = new AtomicReference<>();
        InMemorySessionState store = storeWith(player(PLAYER_1, GO_INDEX, 1500));
        PropertyPurchaseFlow flow = (playerId, propertyId, displayName, price, message, continuation) ->
                capturedContinuation.set(continuation);
        DomainTurnActionGateway gateway = gatewayWithDice(store, flow, 3, 3); // doubles

        gateway.rollDice();

        assertNotNull(capturedContinuation.get());
        assertEquals(TurnContinuationAction.APPLY_TURN_FOLLOW_UP, capturedContinuation.get().completionAction());
    }

    @Test
    void rollDiceSetsSwitchTurnContinuationOnNoDoubles() {
        AtomicReference<TurnContinuationState> capturedContinuation = new AtomicReference<>();
        InMemorySessionState store = storeWith(player(PLAYER_1, GO_INDEX, 1500));
        PropertyPurchaseFlow flow = (playerId, propertyId, displayName, price, message, continuation) ->
                capturedContinuation.set(continuation);
        DomainTurnActionGateway gateway = gatewayWithDice(store, flow, 2, 1); // not doubles, sum=3 → B2

        gateway.rollDice();

        assertNotNull(capturedContinuation.get());
        assertEquals(TurnContinuationAction.END_TURN_WITH_SWITCH, capturedContinuation.get().completionAction());
    }

    // -------------------------------------------------------------------------
    // rollDice — rent
    // -------------------------------------------------------------------------

    @Test
    void rollDiceDeductsRentWhenLandingOnOwnedProperty() {
        // Player 1 at GO, moves 3 → B2 (index 3, owned by player 2)
        // B2 base rent = 4
        PropertyStateSnapshot ownedB2 = new PropertyStateSnapshot("B2", PLAYER_2, false, 0, 0);
        InMemorySessionState store = storeWithTwoPlayers(
                player(PLAYER_1, GO_INDEX, 1500), player(PLAYER_2, 20, 1500), ownedB2);
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1); // sum=3

        gateway.rollDice();

        int expectedRent = 4; // B2 base rent
        assertEquals(1500 - expectedRent, playerById(store.get(), PLAYER_1).cash());
        assertEquals(1500 + expectedRent, playerById(store.get(), PLAYER_2).cash());
        assertEquals(TurnPhase.WAITING_FOR_END_TURN, store.get().turn().phase());
    }

    @Test
    void rollDiceOpensDebtWhenInsufficientCashForRent() {
        // Player 1 at GO with only €2, moves 3 → B2 (owned by player 2, rent=4)
        PropertyStateSnapshot ownedB2 = new PropertyStateSnapshot("B2", PLAYER_2, false, 0, 0);
        InMemorySessionState store = storeWithTwoPlayers(
                player(PLAYER_1, GO_INDEX, 2), player(PLAYER_2, 20, 1500), ownedB2);
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1); // sum=3

        gateway.rollDice();

        assertNotNull(store.get().activeDebt());
        assertEquals(PLAYER_1, store.get().activeDebt().debtorPlayerId());
        assertEquals(4, store.get().activeDebt().amountRemaining());
        assertEquals(TurnPhase.RESOLVING_DEBT, store.get().turn().phase());
    }

    @Test
    void rollDiceSkipsRentOnMortgagedProperty() {
        PropertyStateSnapshot mortgaged = new PropertyStateSnapshot("B2", PLAYER_2, true, 0, 0);
        InMemorySessionState store = storeWithTwoPlayers(
                player(PLAYER_1, GO_INDEX, 1500), player(PLAYER_2, 20, 1500), mortgaged);
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1); // sum=3 → B2

        gateway.rollDice();

        assertEquals(1500, playerById(store.get(), PLAYER_1).cash()); // no rent deducted
        assertEquals(TurnPhase.WAITING_FOR_END_TURN, store.get().turn().phase());
    }

    // -------------------------------------------------------------------------
    // rollDice — tax
    // -------------------------------------------------------------------------

    @Test
    void rollDiceDeductsTaxWhenLandingOnTaxSpot() {
        // Player at index 0, move 4 → TAX1 (index 4, tax=200)
        InMemorySessionState store = storeWith(player(PLAYER_1, GO_INDEX, 1500));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 2); // doubles, sum=4 → TAX1

        gateway.rollDice();

        assertEquals(1500 - 200, playerById(store.get(), PLAYER_1).cash());
    }

    @Test
    void rollDiceOpensDebtForTaxWhenInsufficientCash() {
        InMemorySessionState store = storeWith(player(PLAYER_1, GO_INDEX, 100));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 2); // doubles sum=4 → TAX1 (200)

        gateway.rollDice();

        assertNotNull(store.get().activeDebt());
        assertEquals(200, store.get().activeDebt().amountRemaining());
        assertEquals(TurnPhase.RESOLVING_DEBT, store.get().turn().phase());
    }

    // -------------------------------------------------------------------------
    // rollDice — in-jail
    // -------------------------------------------------------------------------

    @Test
    void rollDiceReleasesFromJailOnDoubles() {
        InMemorySessionState store = storeWith(playerInJail(PLAYER_1, 1500, 3));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 2); // doubles, escape jail

        gateway.rollDice();

        PlayerSnapshot updated = playerById(store.get(), PLAYER_1);
        assertFalse(updated.inJail());
        assertEquals(0, updated.jailRoundsRemaining());
    }

    @Test
    void rollDiceDecrementsJailRoundsOnNoDoubles() {
        InMemorySessionState store = storeWith(playerInJail(PLAYER_1, 1500, 3));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1); // not doubles

        gateway.rollDice();

        PlayerSnapshot updated = playerById(store.get(), PLAYER_1);
        assertTrue(updated.inJail());
        assertEquals(2, updated.jailRoundsRemaining());
        assertEquals(TurnPhase.WAITING_FOR_END_TURN, store.get().turn().phase());
    }

    @Test
    void rollDicePaysJailFineAndMovesOnLastRound() {
        // Last round (jailRoundsRemaining=1) without doubles → pay fine and move
        InMemorySessionState store = storeWith(playerInJail(PLAYER_1, 1500, 1));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1); // not doubles, sum=3

        gateway.rollDice();

        PlayerSnapshot updated = playerById(store.get(), PLAYER_1);
        assertFalse(updated.inJail());
        assertTrue(updated.cash() < 1500); // fine deducted
        assertNotEquals(JAIL_INDEX, updated.boardIndex()); // player moved
    }

    // -------------------------------------------------------------------------
    // endTurn
    // -------------------------------------------------------------------------

    @Test
    void endTurnAdvancesToNextPlayer() {
        InMemorySessionState store = storeWithTwoPlayers(
                player(PLAYER_1, GO_INDEX, 1500), player(PLAYER_2, GO_INDEX, 1500), null);
        DomainTurnActionGateway gateway = gatewayWithDice(store, 1, 2);

        gateway.endTurn();

        assertEquals(PLAYER_2, store.get().turn().activePlayerId());
        assertEquals(TurnPhase.WAITING_FOR_ROLL, store.get().turn().phase());
    }

    @Test
    void endTurnWrapsAroundToFirstPlayer() {
        InMemorySessionState store = storeWithTwoPlayersActiveSeat(
                player(PLAYER_2, GO_INDEX, 1500), player(PLAYER_1, GO_INDEX, 1500), PLAYER_2, null);
        DomainTurnActionGateway gateway = gatewayWithDice(store, 1, 2);

        gateway.endTurn();

        assertEquals(PLAYER_1, store.get().turn().activePlayerId());
    }

    @Test
    void endTurnResetsConsecutiveDoubles() {
        InMemorySessionState store = storeWithTurn(
                player(PLAYER_1, GO_INDEX, 1500),
                new TurnState(PLAYER_1, TurnPhase.WAITING_FOR_END_TURN, false, true, 2)
        );
        DomainTurnActionGateway gateway = gatewayWithDice(store, 1, 2);

        gateway.endTurn();

        assertEquals(0, store.get().turn().consecutiveDoubles());
    }

    // -------------------------------------------------------------------------
    // buyBuildingRound
    // -------------------------------------------------------------------------

    @Test
    void buyBuildingRoundAddHouseAndDeductsCost() {
        // Player owns both brown streets (B1 + B2)
        int housePrice = SpotType.B1.getIntegerProperty("housePrice");
        InMemorySessionState store = storeWithProperties(player(PLAYER_1, GO_INDEX, 1500),
                List.of(
                        new PropertyStateSnapshot("B1", PLAYER_1, false, 0, 0),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 0, 0)
                ));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 1, 2);

        assertTrue(gateway.buyBuildingRound("B1"));

        PropertyStateSnapshot updated = propertyById(store.get(), "B1");
        assertEquals(1, updated.houseCount());
        assertEquals(1500 - housePrice, playerById(store.get(), PLAYER_1).cash());
    }

    @Test
    void buyBuildingRoundRejectedIfPlayerDoesNotOwnFullSet() {
        InMemorySessionState store = storeWithProperties(player(PLAYER_1, GO_INDEX, 1500),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 0, 0)));
        // B2 is unowned — partial color set
        DomainTurnActionGateway gateway = gatewayWithDice(store, 1, 2);

        gateway.buyBuildingRound("B1");

        // No house should be added
        assertEquals(0, propertyById(store.get(), "B1").houseCount());
    }

    @Test
    void buyBuildingRoundUpgradesToHotelAtFiveHouses() {
        InMemorySessionState store = storeWithProperties(player(PLAYER_1, GO_INDEX, 1500),
                List.of(
                        new PropertyStateSnapshot("B1", PLAYER_1, false, 4, 0),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 4, 0)
                ));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 1, 2);

        gateway.buyBuildingRound("B1");

        PropertyStateSnapshot updated = propertyById(store.get(), "B1");
        assertEquals(0, updated.houseCount());
        assertEquals(1, updated.hotelCount());
    }

    @Test
    void buyBuildingRoundRejectedIfInsufficientCash() {
        InMemorySessionState store = storeWithProperties(player(PLAYER_1, GO_INDEX, 10),
                List.of(
                        new PropertyStateSnapshot("B1", PLAYER_1, false, 0, 0),
                        new PropertyStateSnapshot("B2", PLAYER_1, false, 0, 0)
                ));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 1, 2);

        assertFalse(gateway.buyBuildingRound("B1")); // housePrice=50 but player only has 10

        assertEquals(0, propertyById(store.get(), "B1").houseCount());
    }

    @Test
    void buyBuildingRoundRejectedIfMortgagedPropertyInSet() {
        InMemorySessionState store = storeWithProperties(player(PLAYER_1, GO_INDEX, 1500),
                List.of(
                        new PropertyStateSnapshot("B1", PLAYER_1, false, 0, 0),
                        new PropertyStateSnapshot("B2", PLAYER_1, true, 0, 0) // mortgaged
                ));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 1, 2);

        assertFalse(gateway.buyBuildingRound("B1"));

        assertEquals(0, propertyById(store.get(), "B1").houseCount());
    }

    @Test
    void buyBuildingRoundRejectedIfNoBankHousesLeft() {
        // Fill the bank supply: 32 houses on other properties (use dark blue set with 4 houses each — 2 props × 4 = 8)
        // and add more via many properties. Easiest: create 32 properties each with 1 house
        // but board only has real SpotType names. Use 8 properties × 4 houses = 32 total.
        // Dark blue (DB): DB1 and DB2 (2 spots). Orange (O): O1, O2, O3 (3 spots). Green (G): G1, G2, G3. Red (R): R1, R2, R3.
        // Total sets to max 32: e.g., 3 properties × 4 = 12, another 3 × 4 = 12, etc.
        // Simplest: use 8 "other" properties (all valid SpotTypes) each with houseCount=4 = 32 houses.
        List<PropertyStateSnapshot> props = new ArrayList<>();
        // Use 8 distinct street properties owned by PLAYER_2 to fill the bank
        List<String> fillerIds = List.of("O1", "O2", "O3", "LG1", "LG2", "LG3", "LG4", "R1");
        for (String id : fillerIds) {
            props.add(new PropertyStateSnapshot(id, PLAYER_2, false, 4, 0)); // 8 × 4 = 32 houses
        }
        // Target property: PLAYER_1 owns B1 + B2
        props.add(new PropertyStateSnapshot("B1", PLAYER_1, false, 0, 0));
        props.add(new PropertyStateSnapshot("B2", PLAYER_1, false, 0, 0));

        SeatState seat1 = new SeatState("seat-0", 0, PLAYER_1, SeatKind.HUMAN, ControlMode.MANUAL, PLAYER_1, "HUMAN", "#000000");
        SeatState seat2 = new SeatState("seat-1", 1, PLAYER_2, SeatKind.HUMAN, ControlMode.MANUAL, PLAYER_2, "HUMAN", "#FFFFFF");
        PlayerSnapshot p1 = player(PLAYER_1, GO_INDEX, 1500);
        PlayerSnapshot p2 = player(PLAYER_2, GO_INDEX, 1500);
        SessionState state = new SessionState(SESSION_ID, 0L, SessionStatus.IN_PROGRESS,
                List.of(seat1, seat2), List.of(p1, p2), props,
                new TurnState(PLAYER_1, TurnPhase.WAITING_FOR_END_TURN, false, true, 0),
                null, null, null, null, null);
        InMemorySessionState store = new InMemorySessionState(state);
        DomainTurnActionGateway gateway = gatewayWithDice(store, 1, 2);

        assertFalse(gateway.buyBuildingRound("B1")); // 32 houses already out — bank is empty

        assertEquals(0, propertyById(store.get(), "B1").houseCount());
    }

    @Test
    void buyBuildingRoundRejectedIfNoBankHotelsLeft() {
        // Fill 12 hotels on other properties then try to upgrade B1 from 4 houses to hotel
        List<PropertyStateSnapshot> props = new ArrayList<>();
        List<String> hotelIds = List.of("O1", "O2", "O3", "LG1", "LG2", "LG3", "LG4", "R1", "R2", "R3", "DB1", "DB2");
        for (String id : hotelIds) {
            props.add(new PropertyStateSnapshot(id, PLAYER_2, false, 0, 1)); // 12 hotels
        }
        // B1 at 4 houses (one step from hotel), B2 at 4 houses
        props.add(new PropertyStateSnapshot("B1", PLAYER_1, false, 4, 0));
        props.add(new PropertyStateSnapshot("B2", PLAYER_1, false, 4, 0));

        SeatState seat1 = new SeatState("seat-0", 0, PLAYER_1, SeatKind.HUMAN, ControlMode.MANUAL, PLAYER_1, "HUMAN", "#000000");
        SeatState seat2 = new SeatState("seat-1", 1, PLAYER_2, SeatKind.HUMAN, ControlMode.MANUAL, PLAYER_2, "HUMAN", "#FFFFFF");
        PlayerSnapshot p1 = player(PLAYER_1, GO_INDEX, 1500);
        PlayerSnapshot p2 = player(PLAYER_2, GO_INDEX, 1500);
        SessionState state = new SessionState(SESSION_ID, 0L, SessionStatus.IN_PROGRESS,
                List.of(seat1, seat2), List.of(p1, p2), props,
                new TurnState(PLAYER_1, TurnPhase.WAITING_FOR_END_TURN, false, true, 0),
                null, null, null, null, null);
        InMemorySessionState store = new InMemorySessionState(state);
        DomainTurnActionGateway gateway = gatewayWithDice(store, 1, 2);

        assertFalse(gateway.buyBuildingRound("B1")); // 12 hotels already out — bank is empty

        assertEquals(4, propertyById(store.get(), "B1").houseCount()); // unchanged
        assertEquals(0, propertyById(store.get(), "B1").hotelCount());
    }

    // -------------------------------------------------------------------------
    // toggleMortgage
    // -------------------------------------------------------------------------

    @Test
    void toggleMortgageGrantsMortgageValue() {
        int mortgageValue = SpotType.B1.getIntegerProperty("price") / 2; // 60/2=30
        InMemorySessionState store = storeWithProperties(player(PLAYER_1, GO_INDEX, 1500),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, false, 0, 0)));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 1, 2);

        gateway.toggleMortgage("B1");

        assertTrue(propertyById(store.get(), "B1").mortgaged());
        assertEquals(1500 + mortgageValue, playerById(store.get(), PLAYER_1).cash());
    }

    @Test
    void toggleMortgageUnmortgagesAndDeductsCost() {
        int mortgageValue = SpotType.B1.getIntegerProperty("price") / 2; // 30
        int unmortgageCost = mortgageValue + (int) (mortgageValue * 0.1); // 33
        InMemorySessionState store = storeWithProperties(player(PLAYER_1, GO_INDEX, 1500),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, true, 0, 0)));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 1, 2);

        gateway.toggleMortgage("B1");

        assertFalse(propertyById(store.get(), "B1").mortgaged());
        assertEquals(1500 - unmortgageCost, playerById(store.get(), PLAYER_1).cash());
    }

    @Test
    void toggleMortgageRejectedIfInsufficientCashToUnmortgage() {
        InMemorySessionState store = storeWithProperties(player(PLAYER_1, GO_INDEX, 5),
                List.of(new PropertyStateSnapshot("B1", PLAYER_1, true, 0, 0)));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 1, 2);

        gateway.toggleMortgage("B1");

        assertTrue(propertyById(store.get(), "B1").mortgaged()); // still mortgaged
    }

    // -------------------------------------------------------------------------
    // DomainTurnContinuationGateway
    // -------------------------------------------------------------------------

    @Test
    void continuationGatewayEndTurnWithSwitchAdvancesPlayer() {
        InMemorySessionState store = storeWithTwoPlayers(
                player(PLAYER_1, GO_INDEX, 1500), player(PLAYER_2, GO_INDEX, 1500), null);
        DomainTurnContinuationGateway gateway = new DomainTurnContinuationGateway(store);
        TurnContinuationState cont = new TurnContinuationState(
                "cont-1", PLAYER_1, TurnContinuationType.RESUME_AFTER_DEBT,
                TurnContinuationAction.END_TURN_WITH_SWITCH, null, null);

        gateway.resume(cont);

        assertEquals(PLAYER_2, store.get().turn().activePlayerId());
        assertEquals(TurnPhase.WAITING_FOR_ROLL, store.get().turn().phase());
    }

    @Test
    void continuationGatewayApplyFollowUpKeepsSamePlayerAndDoubles() {
        InMemorySessionState store = storeWithTurn(
                player(PLAYER_1, GO_INDEX, 1500),
                new TurnState(PLAYER_1, TurnPhase.RESOLVING_DEBT, false, false, 1)
        );
        DomainTurnContinuationGateway gateway = new DomainTurnContinuationGateway(store);
        TurnContinuationState cont = new TurnContinuationState(
                "cont-1", PLAYER_1, TurnContinuationType.RESUME_AFTER_DEBT,
                TurnContinuationAction.APPLY_TURN_FOLLOW_UP, null, null);

        gateway.resume(cont);

        assertEquals(PLAYER_1, store.get().turn().activePlayerId());
        assertEquals(TurnPhase.WAITING_FOR_ROLL, store.get().turn().phase());
        assertEquals(1, store.get().turn().consecutiveDoubles()); // preserved
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static DomainTurnActionGateway gatewayWithDice(InMemorySessionState store, int die1, int die2) {
        return gatewayWithDice(store, noOpFlow(), die1, die2);
    }

    private static DomainTurnActionGateway gatewayWithDice(InMemorySessionState store, PropertyPurchaseFlow flow, int die1, int die2) {
        int[] dice = {die1, die2};
        int[] idx = {0};
        return new DomainTurnActionGateway(store, flow, () -> dice[idx[0]++ % 2]);
    }

    private static PropertyPurchaseFlow noOpFlow() {
        return (playerId, propertyId, displayName, price, message, continuation) -> {};
    }

    private static InMemorySessionState storeWith(PlayerSnapshot player) {
        return storeWithTurn(player, new TurnState(player.playerId(), TurnPhase.WAITING_FOR_ROLL, true, false, 0));
    }

    private static InMemorySessionState storeWithTurn(PlayerSnapshot player, TurnState turnState) {
        SeatState seat = new SeatState("seat-0", 0, player.playerId(),
                SeatKind.HUMAN, ControlMode.MANUAL, player.name(), "HUMAN", "#000000");
        SessionState state = new SessionState(SESSION_ID, 0L, SessionStatus.IN_PROGRESS,
                List.of(seat), List.of(player), List.of(),
                turnState, null, null, null, null, null);
        return new InMemorySessionState(state);
    }

    private static InMemorySessionState storeWithTwoPlayers(PlayerSnapshot p1, PlayerSnapshot p2,
                                                              PropertyStateSnapshot prop) {
        return storeWithTwoPlayersActiveSeat(p1, p2, p1.playerId(), prop);
    }

    private static InMemorySessionState storeWithTwoPlayersActiveSeat(PlayerSnapshot p1, PlayerSnapshot p2,
                                                                        String activePlayerId,
                                                                        PropertyStateSnapshot prop) {
        List<SeatState> seats = List.of(
                new SeatState("seat-0", 0, p1.playerId(), SeatKind.HUMAN, ControlMode.MANUAL, p1.name(), "HUMAN", "#000000"),
                new SeatState("seat-1", 1, p2.playerId(), SeatKind.HUMAN, ControlMode.MANUAL, p2.name(), "HUMAN", "#FFFFFF")
        );
        List<PropertyStateSnapshot> props = prop != null ? List.of(prop) : List.of();
        SessionState state = new SessionState(SESSION_ID, 0L, SessionStatus.IN_PROGRESS,
                seats, List.of(p1, p2), props,
                new TurnState(activePlayerId, TurnPhase.WAITING_FOR_ROLL, true, false, 0),
                null, null, null, null, null);
        return new InMemorySessionState(state);
    }

    private static InMemorySessionState storeWithProperties(PlayerSnapshot player, List<PropertyStateSnapshot> props) {
        SeatState seat = new SeatState("seat-0", 0, player.playerId(),
                SeatKind.HUMAN, ControlMode.MANUAL, player.name(), "HUMAN", "#000000");
        SessionState state = new SessionState(SESSION_ID, 0L, SessionStatus.IN_PROGRESS,
                List.of(seat), List.of(player), props,
                new TurnState(player.playerId(), TurnPhase.WAITING_FOR_END_TURN, false, true, 0),
                null, null, null, null, null);
        return new InMemorySessionState(state);
    }

    private static PlayerSnapshot player(String playerId, int boardIndex, int cash) {
        int seatIdx = PLAYER_1.equals(playerId) ? 0 : 1;
        return new PlayerSnapshot(playerId, "seat-" + seatIdx, playerId,
                cash, boardIndex, false, false, false, 0, 0, List.of());
    }

    private static PlayerSnapshot playerInJail(String playerId, int cash, int jailRoundsRemaining) {
        return new PlayerSnapshot(playerId, "seat-0", playerId,
                cash, JAIL_INDEX, false, false, true, jailRoundsRemaining, 0, List.of());
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

    // -------------------------------------------------------------------------
    // Card effects — Chance / Community
    // -------------------------------------------------------------------------

    // Board: index 4=TAX1, index 7=CHANCE1; roll 2+1=3 from index 4 lands on CHANCE1.
    private static final int CHANCE1_INDEX = 7;
    private static final int COMMUNITY1_INDEX = 2;

    @Test
    void chanceCardMoneyCollectGivesCash() {
        // MONEY:0 in chance deck = "Your building loan matures. Collect M150" → +150
        InMemorySessionState store = storeWithChanceDeck(player(PLAYER_1, 4, 1500), List.of("MONEY:0"));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1); // sum=3 → index 7 = CHANCE1

        gateway.rollDice();

        assertEquals(1650, playerById(store.get(), PLAYER_1).cash());
        assertEquals(TurnPhase.WAITING_FOR_END_TURN, store.get().turn().phase());
    }

    @Test
    void chanceCardMoneyPayDeductsCash() {
        // MONEY:2 in chance deck = "Speeding fine M15" → -15
        InMemorySessionState store = storeWithChanceDeck(player(PLAYER_1, 4, 1500), List.of("MONEY:2"));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1);

        gateway.rollDice();

        assertEquals(1485, playerById(store.get(), PLAYER_1).cash());
    }

    @Test
    void chanceCardGoJailSendsToJail() {
        InMemorySessionState store = storeWithChanceDeck(player(PLAYER_1, 4, 1500), List.of("GO_JAIL:0"));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1);

        gateway.rollDice();

        PlayerSnapshot updated = playerById(store.get(), PLAYER_1);
        assertTrue(updated.inJail());
        assertEquals(JAIL_INDEX, updated.boardIndex());
    }

    @Test
    void chanceCardOutOfJailAddsCard() {
        InMemorySessionState store = storeWithChanceDeck(player(PLAYER_1, 4, 1500), List.of("OUT_OF_JAIL:0"));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1);

        gateway.rollDice();

        assertEquals(1, playerById(store.get(), PLAYER_1).getOutOfJailCards());
        assertEquals(TurnPhase.WAITING_FOR_END_TURN, store.get().turn().phase());
    }

    @Test
    void chanceCardMoveToGoCollectsGoBonus() {
        // MOVE:1 = "Advance to Go (Collect M200)" → target=GO_SPOT
        InMemorySessionState store = storeWithChanceDeck(player(PLAYER_1, 4, 1500), List.of("MOVE:1"));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1);

        gateway.rollDice();

        PlayerSnapshot updated = playerById(store.get(), PLAYER_1);
        assertEquals(GO_INDEX, updated.boardIndex());
        assertEquals(1700, updated.cash()); // 1500 + 200 GO bonus
    }

    @Test
    void chanceCardMoveNearestRailroadMovesForward() {
        // MOVE_NEAREST:0 = nearest RAILROAD; player at index 4 (TAX1), lands at CHANCE1 (7)
        // nearest railroad from CHANCE1 (7) is RR2 (index 15)
        InMemorySessionState store = storeWithChanceDeck(player(PLAYER_1, 4, 1500), List.of("MOVE_NEAREST:0"));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1);

        gateway.rollDice();

        PlayerSnapshot updated = playerById(store.get(), PLAYER_1);
        SpotType landed = SpotType.SPOT_TYPES.get(updated.boardIndex());
        assertEquals(fi.monopoly.types.PlaceType.RAILROAD, landed.streetType.placeType);
    }

    @Test
    void chanceCardRepairPropertiesChargesForHouses() {
        // REPAIR_PROPERTIES:0 in chance = houseCost=25, hotelCost=100
        // Player owns B1 with 2 houses → repair cost = 2 * 25 = 50
        PropertyStateSnapshot b1 = new PropertyStateSnapshot("B1", PLAYER_1, false, 2, 0);
        InMemorySessionState store = storeWithChanceDeckAndProps(
                player(PLAYER_1, 4, 1500), List.of("REPAIR_PROPERTIES:0"), List.of(b1));
        DomainTurnActionGateway gateway = gatewayWithDice(store, 2, 1);

        gateway.rollDice();

        assertEquals(1450, playerById(store.get(), PLAYER_1).cash()); // 1500 - 50
    }

    @Test
    void communityCardAllPlayersMoneyCommunityCollects() {
        // ALL_PLAYERS_MONEY:0 in community = value=10 (birthday: collect M10 from each)
        // Two players: active player (PLAYER_1) collects 10 from PLAYER_2 → +10
        InMemorySessionState store = storeWithCommunityDeckTwoPlayers(
                player(PLAYER_1, 0, 1500), player(PLAYER_2, 0, 1000),
                List.of("ALL_PLAYERS_MONEY:0")); // player at 0 + dice 2 → COMMUNITY1 (index 2)
        DomainTurnActionGateway gateway = gatewayWithDice(store, 1, 1); // sum=2, doubles

        gateway.rollDice();

        assertEquals(1510, playerById(store.get(), PLAYER_1).cash()); // +10
        assertEquals(990, playerById(store.get(), PLAYER_2).cash());   // -10
    }

    // -------------------------------------------------------------------------
    // Helpers for card tests
    // -------------------------------------------------------------------------

    private static InMemorySessionState storeWithChanceDeck(PlayerSnapshot player, List<String> chanceDeck) {
        return storeWithChanceDeckAndProps(player, chanceDeck, List.of());
    }

    private static InMemorySessionState storeWithChanceDeckAndProps(PlayerSnapshot player,
                                                                     List<String> chanceDeck,
                                                                     List<PropertyStateSnapshot> props) {
        SeatState seat = new SeatState("seat-0", 0, player.playerId(),
                SeatKind.HUMAN, ControlMode.MANUAL, player.name(), "HUMAN", "#000000");
        SessionState state = SessionState.builder()
                .sessionId(SESSION_ID).version(0L).status(SessionStatus.IN_PROGRESS)
                .seats(List.of(seat)).players(List.of(player)).properties(props)
                .turn(new TurnState(player.playerId(), TurnPhase.WAITING_FOR_ROLL, true, false, 0))
                .chanceDeck(chanceDeck).communityDeck(List.of())
                .build();
        return new InMemorySessionState(state);
    }

    private static InMemorySessionState storeWithCommunityDeckTwoPlayers(PlayerSnapshot p1, PlayerSnapshot p2,
                                                                           List<String> communityDeck) {
        List<SeatState> seats = List.of(
                new SeatState("seat-0", 0, p1.playerId(), SeatKind.HUMAN, ControlMode.MANUAL, p1.name(), "HUMAN", "#000000"),
                new SeatState("seat-1", 1, p2.playerId(), SeatKind.HUMAN, ControlMode.MANUAL, p2.name(), "HUMAN", "#FFFFFF")
        );
        SessionState state = SessionState.builder()
                .sessionId(SESSION_ID).version(0L).status(SessionStatus.IN_PROGRESS)
                .seats(seats).players(List.of(p1, p2)).properties(List.of())
                .turn(new TurnState(p1.playerId(), TurnPhase.WAITING_FOR_ROLL, true, false, 0))
                .chanceDeck(List.of()).communityDeck(communityDeck)
                .build();
        return new InMemorySessionState(state);
    }
}
