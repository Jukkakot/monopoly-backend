package fi.monopoly.application.session.turn;

import fi.monopoly.application.command.BuyBuildingRoundCommand;
import fi.monopoly.application.command.EndTurnCommand;
import fi.monopoly.application.command.RollDiceCommand;
import fi.monopoly.application.command.ToggleMortgageCommand;
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

    private SessionState sessionState(TurnPhase phase) {
        return new SessionState(
                "local-session",
                0L,
                SessionStatus.IN_PROGRESS,
                List.of(new SeatState("seat-1", 0, "player-1", SeatKind.BOT, ControlMode.MANUAL, "Bot", "STRONG", "#FFC0CB")),
                List.of(new PlayerSnapshot("player-1", "seat-1", "Bot", 1500, SpotType.GO_SPOT.ordinal(), false, false, false, 0, 0, List.of())),
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
        private boolean toggledMortgage;

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
        public boolean toggleMortgage(String propertyId) {
            toggledMortgage = SpotType.RR1.name().equals(propertyId);
            return toggledMortgage;
        }

        @Override
        public boolean useGetOutOfJailCard() {
            return true;
        }
    }
}
