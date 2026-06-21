package fi.monopoly.application.session.turn;

import fi.monopoly.application.command.BuyBuildingRoundCommand;
import fi.monopoly.application.command.EndTurnCommand;
import fi.monopoly.application.command.PayJailFineCommand;
import fi.monopoly.application.command.RollDiceCommand;
import fi.monopoly.application.command.ToggleMortgageCommand;
import fi.monopoly.application.command.UseGetOutOfJailCardCommand;
import fi.monopoly.domain.session.ControlMode;
import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.domain.session.PropertyStateSnapshot;
import fi.monopoly.domain.session.SeatKind;
import fi.monopoly.domain.session.SeatState;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.session.SessionStatus;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.types.SpotType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TurnActionCommandHandlerTest {

    @Test
    void acceptsRollDiceForActivePlayerDuringRollingPhase() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session", () -> sessionState(TurnPhase.WAITING_FOR_ROLL), gateway);

        assertTrue(handler.handle(new RollDiceCommand("local-session", "player-1")).accepted());
        assertTrue(gateway.rolledDice);
    }

    @Test
    void allowsRollDiceDuringUnknownPhaseForLegacyProjectionSeam() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session", () -> sessionState(TurnPhase.UNKNOWN), gateway);

        assertTrue(handler.handle(new RollDiceCommand("local-session", "player-1")).accepted());
        assertTrue(gateway.rolledDice);
    }

    @Test
    void rejectsRollDiceWhileDecisionIsPending() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session", () -> sessionState(TurnPhase.WAITING_FOR_DECISION), gateway);

        assertFalse(handler.handle(new RollDiceCommand("local-session", "player-1")).accepted());
        assertFalse(gateway.rolledDice);
    }

    @Test
    void rejectsEndTurnOutsideEndingPhase() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session", () -> sessionState(TurnPhase.WAITING_FOR_DECISION), gateway);

        assertFalse(handler.handle(new EndTurnCommand("local-session", "player-1")).accepted());
        assertFalse(gateway.endedTurn);
    }

    @Test
    void allowsEndTurnDuringUnknownPhaseForLegacyProjectionSeam() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session", () -> sessionState(TurnPhase.UNKNOWN), gateway);

        assertTrue(handler.handle(new EndTurnCommand("local-session", "player-1")).accepted());
        assertTrue(gateway.endedTurn);
    }

    @Test
    void buysBuildingRoundThroughGatewayPropertyLookup() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session", () -> sessionState(TurnPhase.WAITING_FOR_END_TURN), gateway);

        assertTrue(handler.handle(new BuyBuildingRoundCommand("local-session", "player-1", SpotType.O1.name())).accepted());
        assertTrue(gateway.boughtBuildingRound);
    }

    @Test
    void togglesMortgageThroughGatewayLookup() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session",
                () -> sessionStateWithProperty(TurnPhase.WAITING_FOR_END_TURN, SpotType.RR1.name(), false, 1500), gateway);

        assertTrue(handler.handle(new ToggleMortgageCommand("local-session", "player-1", SpotType.RR1.name())).accepted());
        assertTrue(gateway.toggledMortgage);
    }

    @Test
    void rejectsMortgageToggleForUnownedProperty() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session",
                () -> sessionState(TurnPhase.WAITING_FOR_END_TURN), gateway);

        assertFalse(handler.handle(new ToggleMortgageCommand("local-session", "player-1", SpotType.RR1.name())).accepted());
        assertFalse(gateway.toggledMortgage);
    }

    @Test
    void rejectsUnmortgageWhenInsufficientFunds() {
        FakeGateway gateway = new FakeGateway();
        // RR1 price = 200, mortgageValue = 100, unmortgage cost = 110
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session",
                () -> sessionStateWithProperty(TurnPhase.WAITING_FOR_END_TURN, SpotType.RR1.name(), true, 50), gateway);

        var result = handler.handle(new ToggleMortgageCommand("local-session", "player-1", SpotType.RR1.name()));
        assertFalse(result.accepted());
        assertEquals("INSUFFICIENT_FUNDS", result.rejections().getFirst().code());
        assertFalse(gateway.toggledMortgage);
    }

    // -------------------------------------------------------------------------
    // UseGetOutOfJailCard
    // -------------------------------------------------------------------------

    @Test
    void acceptsUseGetOutOfJailCardWhenInJailAndHasCard() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session",
                () -> sessionStateInJail(TurnPhase.WAITING_FOR_ROLL, 1, 200), gateway);

        var result = handler.handle(new UseGetOutOfJailCardCommand("local-session", "player-1"));
        assertTrue(result.accepted());
        assertTrue(gateway.usedJailCard);
    }

    @Test
    void rejectsUseGetOutOfJailCardWhenNotInJail() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session",
                () -> sessionState(TurnPhase.WAITING_FOR_ROLL), gateway);

        var result = handler.handle(new UseGetOutOfJailCardCommand("local-session", "player-1"));
        assertFalse(result.accepted());
        assertEquals("NOT_IN_JAIL", result.rejections().getFirst().code());
        assertFalse(gateway.usedJailCard);
    }

    @Test
    void rejectsUseGetOutOfJailCardWhenNoCard() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session",
                () -> sessionStateInJail(TurnPhase.WAITING_FOR_ROLL, 0, 200), gateway);

        var result = handler.handle(new UseGetOutOfJailCardCommand("local-session", "player-1"));
        assertFalse(result.accepted());
        assertEquals("NO_CARD", result.rejections().getFirst().code());
        assertFalse(gateway.usedJailCard);
    }

    @Test
    void rejectsUseGetOutOfJailCardInWrongPhase() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session",
                () -> sessionStateInJail(TurnPhase.WAITING_FOR_END_TURN, 1, 200), gateway);

        var result = handler.handle(new UseGetOutOfJailCardCommand("local-session", "player-1"));
        assertFalse(result.accepted());
        assertEquals("CARD_NOT_ALLOWED", result.rejections().getFirst().code());
        assertFalse(gateway.usedJailCard);
    }

    @Test
    void rejectsUseGetOutOfJailCardForWrongActor() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session",
                () -> sessionStateInJail(TurnPhase.WAITING_FOR_ROLL, 1, 200), gateway);

        var result = handler.handle(new UseGetOutOfJailCardCommand("local-session", "player-2"));
        assertFalse(result.accepted());
        assertEquals("WRONG_TURN_ACTOR", result.rejections().getFirst().code());
        assertFalse(gateway.usedJailCard);
    }

    // -------------------------------------------------------------------------
    // PayJailFine
    // -------------------------------------------------------------------------

    @Test
    void acceptsPayJailFineWhenInJailAndHasCash() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session",
                () -> sessionStateInJail(TurnPhase.WAITING_FOR_ROLL, 0, 200), gateway);

        var result = handler.handle(new PayJailFineCommand("local-session", "player-1"));
        assertTrue(result.accepted());
        assertTrue(gateway.paidJailFine);
    }

    @Test
    void rejectsPayJailFineWhenNotInJail() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session",
                () -> sessionState(TurnPhase.WAITING_FOR_ROLL), gateway);

        var result = handler.handle(new PayJailFineCommand("local-session", "player-1"));
        assertFalse(result.accepted());
        assertEquals("NOT_IN_JAIL", result.rejections().getFirst().code());
        assertFalse(gateway.paidJailFine);
    }

    @Test
    void rejectsPayJailFineWhenInsufficientCash() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session",
                () -> sessionStateInJail(TurnPhase.WAITING_FOR_ROLL, 0, 30), gateway);

        var result = handler.handle(new PayJailFineCommand("local-session", "player-1"));
        assertFalse(result.accepted());
        assertEquals("INSUFFICIENT_FUNDS", result.rejections().getFirst().code());
        assertFalse(gateway.paidJailFine);
    }

    @Test
    void rejectsPayJailFineInWrongPhase() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session",
                () -> sessionStateInJail(TurnPhase.WAITING_FOR_END_TURN, 0, 200), gateway);

        var result = handler.handle(new PayJailFineCommand("local-session", "player-1"));
        assertFalse(result.accepted());
        assertEquals("FINE_NOT_ALLOWED", result.rejections().getFirst().code());
        assertFalse(gateway.paidJailFine);
    }

    @Test
    void rejectsPayJailFineForWrongActor() {
        FakeGateway gateway = new FakeGateway();
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session",
                () -> sessionStateInJail(TurnPhase.WAITING_FOR_ROLL, 0, 200), gateway);

        var result = handler.handle(new PayJailFineCommand("local-session", "player-2"));
        assertFalse(result.accepted());
        assertEquals("WRONG_TURN_ACTOR", result.rejections().getFirst().code());
        assertFalse(gateway.paidJailFine);
    }

    @Test
    void rejectsBuyBuildingWhenBankHouseSupplyExhausted() {
        FakeGateway gateway = new FakeGateway();
        // O1 has 0 houses; 8 other properties each have 4 houses = 32 total (bank limit)
        List<PropertyStateSnapshot> props = new java.util.ArrayList<>();
        props.add(new PropertyStateSnapshot(SpotType.O1.name(), "player-1", false, 0, 0));
        SpotType[] fullHouseProps = {SpotType.O2, SpotType.O3, SpotType.B1, SpotType.B2, SpotType.G1, SpotType.G2, SpotType.Y1, SpotType.Y2};
        for (SpotType s : fullHouseProps) props.add(new PropertyStateSnapshot(s.name(), "player-1", false, 4, 0));
        SessionState state = sessionStateWithProps(TurnPhase.WAITING_FOR_END_TURN, props, 1500);
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session", () -> state, gateway);

        var result = handler.handle(new BuyBuildingRoundCommand("local-session", "player-1", SpotType.O1.name()));
        assertFalse(result.accepted(), "Should reject when bank has no houses left");
        assertEquals("BANK_SUPPLY_EXHAUSTED", result.rejections().getFirst().code());
        assertFalse(gateway.boughtBuildingRound);
    }

    @Test
    void rejectsBuyBuildingWhenBankHotelSupplyExhausted() {
        FakeGateway gateway = new FakeGateway();
        // O1 has 4 houses (next buy would be a hotel); 12 other properties each have 1 hotel = 12 total (bank limit)
        List<PropertyStateSnapshot> props = new java.util.ArrayList<>();
        props.add(new PropertyStateSnapshot(SpotType.O1.name(), "player-1", false, 4, 0));
        SpotType[] fullHotelProps = {SpotType.O2, SpotType.O3, SpotType.B1, SpotType.B2, SpotType.G1, SpotType.G2,
                SpotType.Y1, SpotType.Y2, SpotType.Y3, SpotType.R1, SpotType.R2, SpotType.R3};
        for (SpotType s : fullHotelProps) props.add(new PropertyStateSnapshot(s.name(), "player-1", false, 0, 1));
        SessionState state = sessionStateWithProps(TurnPhase.WAITING_FOR_END_TURN, props, 1500);
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session", () -> state, gateway);

        var result = handler.handle(new BuyBuildingRoundCommand("local-session", "player-1", SpotType.O1.name()));
        assertFalse(result.accepted(), "Should reject when bank has no hotels left");
        assertEquals("BANK_SUPPLY_EXHAUSTED", result.rejections().getFirst().code());
        assertFalse(gateway.boughtBuildingRound);
    }

    @Test
    void rejectsMortgageOfStreetWhenColorGroupHasBuildings() {
        FakeGateway gateway = new FakeGateway();
        // B1 has 1 house, B2 has no house; player has plenty of cash
        SessionState state = sessionStateWithTwoStreetProperties(
                TurnPhase.WAITING_FOR_END_TURN,
                SpotType.B1.name(), false, 1,
                SpotType.B2.name(), false, 0,
                1500);
        TurnActionCommandHandler handler = new TurnActionCommandHandler("local-session", () -> state, gateway);

        var result = handler.handle(new ToggleMortgageCommand("local-session", "player-1", SpotType.B2.name()));
        assertFalse(result.accepted(), "Should reject mortgage when color group has buildings");
        assertEquals("BUILDINGS_PRESENT", result.rejections().getFirst().code());
        assertFalse(gateway.toggledMortgage);
    }

    private SessionState sessionStateWithProps(TurnPhase phase, List<PropertyStateSnapshot> props, int playerCash) {
        return new SessionState(
                "local-session", 0L, SessionStatus.IN_PROGRESS,
                List.of(new SeatState("seat-1", 0, "player-1", SeatKind.BOT, ControlMode.MANUAL, "Bot", "STRONG", "#FFC0CB")),
                List.of(new PlayerSnapshot("player-1", "seat-1", "Bot", playerCash, SpotType.GO_SPOT.ordinal(), false, false, false, 0, 0, List.of())),
                props,
                new TurnState("player-1", phase, false, true),
                null, null, null, null, null);
    }

    private SessionState sessionState(TurnPhase phase) {
        boolean canRoll = phase == TurnPhase.WAITING_FOR_ROLL || phase == TurnPhase.UNKNOWN;
        boolean canEndTurn = phase == TurnPhase.WAITING_FOR_END_TURN || phase == TurnPhase.UNKNOWN;
        return new SessionState(
                "local-session",
                0L,
                SessionStatus.IN_PROGRESS,
                List.of(new SeatState("seat-1", 0, "player-1", SeatKind.BOT, ControlMode.MANUAL, "Bot", "STRONG", "#FFC0CB")),
                List.of(new PlayerSnapshot("player-1", "seat-1", "Bot", 1500, SpotType.GO_SPOT.ordinal(), false, false, false, 0, 0, List.of())),
                List.of(),
                new TurnState("player-1", phase, canRoll, canEndTurn),
                null,
                null,
                null,
                null,
                null
        );
    }

    private SessionState sessionStateInJail(TurnPhase phase, int jailCards, int cash) {
        return new SessionState(
                "local-session",
                0L,
                SessionStatus.IN_PROGRESS,
                List.of(new SeatState("seat-1", 0, "player-1", SeatKind.BOT, ControlMode.MANUAL, "Bot", "STRONG", "#FFC0CB")),
                List.of(new PlayerSnapshot("player-1", "seat-1", "Bot", cash, 10 /* JAIL */, false, false, true, 2, jailCards, List.of())),
                List.of(),
                new TurnState("player-1", phase, false, false),
                null,
                null,
                null,
                null,
                null
        );
    }

    private SessionState sessionStateWithProperty(TurnPhase phase, String propertyId, boolean mortgaged, int playerCash) {
        return new SessionState(
                "local-session",
                0L,
                SessionStatus.IN_PROGRESS,
                List.of(new SeatState("seat-1", 0, "player-1", SeatKind.BOT, ControlMode.MANUAL, "Bot", "STRONG", "#FFC0CB")),
                List.of(new PlayerSnapshot("player-1", "seat-1", "Bot", playerCash, SpotType.GO_SPOT.ordinal(), false, false, false, 0, 0, List.of())),
                List.of(new PropertyStateSnapshot(propertyId, "player-1", mortgaged, 0, 0)),
                new TurnState("player-1", phase, false, false),
                null,
                null,
                null,
                null,
                null
        );
    }

    private SessionState sessionStateWithTwoStreetProperties(
            TurnPhase phase,
            String prop1Id, boolean mortgaged1, int houses1,
            String prop2Id, boolean mortgaged2, int houses2,
            int playerCash) {
        return new SessionState(
                "local-session", 0L, SessionStatus.IN_PROGRESS,
                List.of(new SeatState("seat-1", 0, "player-1", SeatKind.BOT, ControlMode.MANUAL, "Bot", "STRONG", "#FFC0CB")),
                List.of(new PlayerSnapshot("player-1", "seat-1", "Bot", playerCash, SpotType.GO_SPOT.ordinal(), false, false, false, 0, 0, List.of())),
                List.of(
                        new PropertyStateSnapshot(prop1Id, "player-1", mortgaged1, houses1, 0),
                        new PropertyStateSnapshot(prop2Id, "player-1", mortgaged2, houses2, 0)
                ),
                new TurnState("player-1", phase, false, false),
                null, null, null, null, null);
    }

    private static final class FakeGateway implements TurnActionGateway {
        private boolean rolledDice;
        private boolean endedTurn;
        private boolean boughtBuildingRound;
        private boolean soldBuildingRound;
        private boolean toggledMortgage;
        private boolean usedJailCard;
        private boolean paidJailFine;

        @Override
        public boolean rollDice() {
            rolledDice = true;
            return true;
        }

        @Override
        public boolean endTurn() {
            endedTurn = true;
            return true;
        }

        @Override
        public boolean buyBuildingRound(String propertyId) {
            boughtBuildingRound = SpotType.O1.name().equals(propertyId);
            return boughtBuildingRound;
        }

        @Override
        public boolean sellBuildingRound(String propertyId) {
            soldBuildingRound = SpotType.O1.name().equals(propertyId);
            return soldBuildingRound;
        }

        @Override
        public boolean toggleMortgage(String propertyId) {
            toggledMortgage = SpotType.RR1.name().equals(propertyId);
            return toggledMortgage;
        }

        @Override
        public boolean useGetOutOfJailCard() {
            usedJailCard = true;
            return true;
        }

        @Override
        public boolean payJailFine() {
            paidJailFine = true;
            return true;
        }

        @Override
        public boolean acknowledgeCard() {
            return true;
        }
    }
}
