package fi.monopoly.application.session.trade;

import fi.monopoly.application.command.*;
import fi.monopoly.application.session.InMemorySessionState;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.domain.session.*;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TradeCommandHandlerTest {

    private static final String SESSION_ID = "test-session";
    private static final String TRADE_ID = "trade-123";
    private static final String P1 = "player-1";
    private static final String P2 = "player-2";

    private InMemorySessionState store;
    private TradeCommandHandler handler;

    @BeforeEach
    void setUp() {
        List<SeatState> seats = List.of(seat(P1, 0), seat(P2, 1));
        SessionState initial = new SessionState(
                SESSION_ID, 0L, SessionStatus.IN_PROGRESS,
                seats, List.of(player(P1, 500), player(P2, 500)), List.of(),
                new TurnState(P1, TurnPhase.WAITING_FOR_ROLL, false, false, 0),
                null, null, null, null, null
        );
        store = new InMemorySessionState(initial);
        handler = new TradeCommandHandler(
                SESSION_ID,
                store::get,
                trade -> store.update(s -> s.toBuilder().tradeState(trade).build()),
                new DomainTradeGateway(store)
        );
    }

    // -------------------------------------------------------------------------
    // Counter routing
    // -------------------------------------------------------------------------

    @Test
    void counterMakesActorTheEditor() {
        setTrade(submittedTrade(P2, P1));
        CommandResult result = handler.handle(new CounterTradeCommand(SESSION_ID, P1, TRADE_ID));

        assertTrue(result.accepted(), "Counter should succeed");
        TradeState state = store.get().tradeState();
        assertEquals(TradeStatus.COUNTERED, state.status());
        assertEquals(P1, state.editingPlayerId(), "Counter-offerer should become editor");
    }

    @Test
    void counterSetsDecisionRequiredToNull() {
        setTrade(submittedTrade(P2, P1));
        handler.handle(new CounterTradeCommand(SESSION_ID, P1, TRADE_ID));

        TradeState state = store.get().tradeState();
        assertNull(state.decisionRequiredFromPlayerId(),
                "No decision required until counter-offerer submits");
    }

    // -------------------------------------------------------------------------
    // Actor cannot accept own counter
    // -------------------------------------------------------------------------

    @Test
    void actorCannotAcceptDuringCounteredPhase() {
        setTrade(counteredTrade(P2, P1, P1));
        CommandResult result = handler.handle(new AcceptTradeCommand(SESSION_ID, P1, TRADE_ID));
        assertFalse(result.accepted(), "Counter-offerer must not accept while COUNTERED");
    }

    @Test
    void otherPartyCannotAcceptDuringCounteredPhase() {
        setTrade(counteredTrade(P2, P1, P1));
        CommandResult result = handler.handle(new AcceptTradeCommand(SESSION_ID, P2, TRADE_ID));
        assertFalse(result.accepted(), "Nobody can accept while COUNTERED — editor must submit first");
    }

    // -------------------------------------------------------------------------
    // Full counter round-trip
    // -------------------------------------------------------------------------

    @Test
    void counterSubmitRoutesDecisionToOtherParty() {
        setTrade(submittedTrade(P2, P1));
        handler.handle(new CounterTradeCommand(SESSION_ID, P1, TRADE_ID));
        handler.handle(new SubmitTradeOfferCommand(SESSION_ID, P1, TRADE_ID));

        TradeState state = store.get().tradeState();
        assertEquals(TradeStatus.SUBMITTED, state.status());
        assertEquals(P2, state.decisionRequiredFromPlayerId(),
                "After P1 submits counter, P2 should need to decide");
    }

    @Test
    void originalSubmitterCanAcceptCounterAfterItIsSubmitted() {
        setTrade(submittedTrade(P2, P1));
        handler.handle(new CounterTradeCommand(SESSION_ID, P1, TRADE_ID));
        handler.handle(new SubmitTradeOfferCommand(SESSION_ID, P1, TRADE_ID));
        CommandResult accept = handler.handle(new AcceptTradeCommand(SESSION_ID, P2, TRADE_ID));

        assertTrue(accept.accepted(), "P2 can accept P1's counter-offer after it is submitted");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void setTrade(TradeState trade) {
        store.update(s -> s.toBuilder().tradeState(trade).build());
    }

    private static TradeState submittedTrade(String initiator, String recipient) {
        TradeOfferState offer = new TradeOfferState(
                initiator, recipient,
                new TradeSelectionState(200, List.of(), 0),
                TradeSelectionState.NONE
        );
        return new TradeState(TRADE_ID, initiator, recipient, TradeStatus.SUBMITTED,
                offer, recipient, false, recipient, initiator, List.of());
    }

    private static TradeState counteredTrade(String initiator, String recipient, String editor) {
        TradeOfferState offer = new TradeOfferState(
                initiator, recipient,
                new TradeSelectionState(200, List.of(), 0),
                TradeSelectionState.NONE
        );
        return new TradeState(TRADE_ID, initiator, recipient, TradeStatus.COUNTERED,
                offer, editor, false, null, initiator, List.of());
    }

    private static PlayerSnapshot player(String id, int cash) {
        int idx = P1.equals(id) ? 0 : 1;
        return new PlayerSnapshot(id, "seat-" + idx, id, cash, 0, false, false, false, 0, 0, List.of());
    }

    private static SeatState seat(String playerId, int index) {
        return new SeatState("seat-" + index, index, playerId,
                SeatKind.HUMAN, ControlMode.MANUAL, playerId, "HUMAN", "#000000");
    }
}
