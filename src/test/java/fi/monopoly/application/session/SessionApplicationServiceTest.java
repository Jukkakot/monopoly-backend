package fi.monopoly.application.session;

import fi.monopoly.application.command.RefreshSessionViewCommand;
import fi.monopoly.domain.decision.DecisionAction;
import fi.monopoly.domain.decision.DecisionType;
import fi.monopoly.domain.decision.PendingDecision;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SessionApplicationServiceTest {

    @Test
    void refreshCommandReturnsAcceptedProjectedState() {
        SessionState expectedState = sampleState();
        SessionApplicationService service = new SessionApplicationService("local-session", () -> expectedState);

        var result = service.handle(new RefreshSessionViewCommand("local-session"));

        assertTrue(result.accepted());
        assertEquals(expectedState, result.sessionState());
        assertTrue(result.rejections().isEmpty());
    }

    @Test
    void refreshCommandRejectsWrongSessionId() {
        SessionApplicationService service = new SessionApplicationService("local-session", this::sampleState);

        var result = service.handle(new RefreshSessionViewCommand("other-session"));

        assertFalse(result.accepted());
        assertEquals(1, result.rejections().size());
        assertEquals("WRONG_SESSION", result.rejections().get(0).code());
    }

    @Test
    void restoreFromSeedsAuthoritativeSubsystemOverrides() {
        SessionApplicationService service = new SessionApplicationService("local-session", this::sampleState);
        SessionState restoredState = new SessionState(
                "local-session",
                4L,
                SessionStatus.IN_PROGRESS,
                sampleState().seats(),
                sampleState().players(),
                sampleState().properties(),
                new TurnState("player-0", TurnPhase.WAITING_FOR_DECISION, false, false),
                new PendingDecision(
                        "decision-1",
                        DecisionType.GENERIC_CONFIRM,
                        "player-0",
                        List.of(DecisionAction.PRIMARY),
                        "Confirm something",
                        null
                ),
                new AuctionState("auction-1", "B1", "player-0", "player-0", null, 0, 10, Set.of(), List.of("player-0"), AuctionStatus.ACTIVE, 0, null),
                new DebtStateModel("debt-1", "player-0", DebtCreditorType.BANK, null, 100, "Debt", false, 50, 0, List.of(DebtAction.PAY_DEBT_NOW)),
                new TradeState(
                        "trade-1",
                        "player-0",
                        "player-1",
                        TradeStatus.EDITING,
                        null,
                        "player-0",
                        true,
                        "player-1",
                        "player-0",
                        List.of()
                ),
                new TurnContinuationState(
                        "continuation-restore",
                        "player-0",
                        TurnContinuationType.RESUME_AFTER_DEBT,
                        TurnContinuationAction.APPLY_TURN_FOLLOW_UP,
                        null,
                        "Resume after debt resolution"
                ),
                null
        );

        service.restoreFrom(restoredState);
        SessionState currentState = service.currentState();

        assertEquals(restoredState.pendingDecision(), currentState.pendingDecision());
        assertEquals(restoredState.auctionState(), currentState.auctionState());
        assertEquals(restoredState.activeDebt(), currentState.activeDebt());
        assertEquals(restoredState.tradeState(), currentState.tradeState());
        assertEquals(restoredState.turnContinuationState(), currentState.turnContinuationState());
        assertEquals(TurnPhase.RESOLVING_DEBT, currentState.turn().phase());
    }

    @Test
    void explicitTurnContinuationOverrideIsReflectedInCurrentState() {
        SessionApplicationService service = new SessionApplicationService("local-session", this::sampleState);
        TurnContinuationState continuationState = new TurnContinuationState(
                "continuation-1",
                "player-0",
                TurnContinuationType.RESUME_AFTER_AUCTION,
                TurnContinuationAction.APPLY_TURN_FOLLOW_UP,
                "B1",
                "Resume current turn"
        );

        service.setTurnContinuationOverride(continuationState);

        assertEquals(continuationState, service.currentState().turnContinuationState());
    }

    private SessionState sampleState() {
        return new SessionState(
                "local-session",
                0L,
                SessionStatus.IN_PROGRESS,
                List.of(new SeatState("seat-0", 0, "player-0", SeatKind.HUMAN, ControlMode.MANUAL, "Human", "HUMAN", "#000000")),
                List.of(new PlayerSnapshot("player-0", "seat-0", "Human", 1500, -1, false, false, false, 0, 0, List.of())),
                List.of(),
                new TurnState("player-0", TurnPhase.WAITING_FOR_ROLL, true, false),
                null,
                null,
                null,
                null,
                null
        );
    }
}
