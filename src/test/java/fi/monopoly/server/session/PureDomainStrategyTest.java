package fi.monopoly.server.session;

import fi.monopoly.domain.session.TradeOfferState;
import fi.monopoly.domain.session.TradeSelectionState;
import fi.monopoly.domain.session.TradeState;
import fi.monopoly.domain.session.TradeStatus;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.server.bot.BotMemory;
import fi.monopoly.server.bot.Intent;
import fi.monopoly.utils.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1.2: smoke tests for {@link PureDomainStrategy#decide}.
 *
 * <p>These use {@link TestSessionState} to construct board states and assert that the
 * strategy returns the expected {@link Intent}. They are not exhaustive — the golden-master
 * test ({@link BotGoldenMasterTest}) owns behavioural correctness; these tests validate the
 * basic contract of the new {@code BotStrategy} seam.</p>
 */
class PureDomainStrategyTest {

    private final PureDomainStrategy strategy = new PureDomainStrategy(Map.of());
    private final RandomSource rng = RandomSource.seeded(99L);

    @Test
    void rollIntentOnWaitingForRoll() {
        var state = TestSessionState.twoPlayerGame()
                .withPhase(TurnPhase.WAITING_FOR_ROLL)
                .build();
        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.Roll.class, intent);
    }

    @Test
    void acknowledgeCardIntentOnWaitingForCardAck() {
        var state = TestSessionState.twoPlayerGame()
                .withPhase(TurnPhase.WAITING_FOR_CARD_ACK)
                .build();
        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.AcknowledgeCard.class, intent);
    }

    @Test
    void endTurnIntentWhenNothingToBuild() {
        // No properties owned → no build/trade opportunities → EndTurn
        var state = TestSessionState.twoPlayerGame()
                .withPhase(TurnPhase.WAITING_FOR_END_TURN)
                .build();
        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.EndTurn.class, intent);
    }

    @Test
    void sameSeedProducesSameIntentSequence() {
        var state = TestSessionState.twoPlayerGame()
                .withPhase(TurnPhase.WAITING_FOR_ROLL)
                .build();
        Intent a = strategy.decide(state, "player-1", BotMemory.empty(), RandomSource.seeded(7L));
        Intent b = strategy.decide(state, "player-1", BotMemory.empty(), RandomSource.seeded(7L));
        assertEquals(a.getClass(), b.getClass(), "Same seed must yield same intent class");
    }

    @Test
    void differentBotsGetIndependentMemory() {
        BotMemory mem1 = BotMemory.empty();
        BotMemory mem2 = BotMemory.empty();
        mem1.recordDecline("partner-x");
        assertEquals(0, mem2.declineCount("partner-x"), "BotMemory instances must be independent");
    }

    // -------------------------------------------------------------------------
    // Trade-loop regression: the opener must learn its trade was killed, even when
    // the partner cancels during counter-editing (decisionRequiredFromPlayerId == null),
    // so it never re-proposes the identical trade indefinitely.
    // -------------------------------------------------------------------------

    /** A opened a trade requesting a property from B; B countered and then cancelled. */
    private static TradeState counteredTradeOpenedByA(String targetPropId, TradeSelectionState aGave) {
        return new TradeState(
                "trade-1", "bot-a", "bot-b", TradeStatus.COUNTERED,
                new TradeOfferState("bot-a", "bot-b",
                        aGave,
                        new TradeSelectionState(0, List.of(targetPropId), 0)),
                "bot-b", false,
                null,       // decisionRequiredFromPlayerId is null in COUNTERED state
                "bot-a", List.of());
    }

    @Test
    void openerMemoryRecordsDeclineWhenPartnerCancelsCounteredCashOffer() {
        BotMemory openerMemory = BotMemory.empty();
        Map<String, BotMemory> memories = Map.of("bot-a", openerMemory, "bot-b", BotMemory.empty());
        TradeState trade = counteredTradeOpenedByA("KATAJANOKKA",
                new TradeSelectionState(200, List.of(), 0));

        BotMemory.recordTradeKilledByPartner(memories, trade, "bot-b");

        assertEquals(1, openerMemory.declineCount("bot-b"),
                "counter-cancel must count as a decline for the opener");
        assertEquals(200, openerMemory.lastDeclinedAmount("bot-b", "KATAJANOKKA"),
                "the declined cash offer must be recorded so re-offers must strictly beat it");
        assertTrue(openerMemory.declinedSwapTargets("bot-b").isEmpty(),
                "a cash-only decline must NOT block the target — a swap escalation is still allowed");
    }

    @Test
    void openerStopsRetargetingPropertyAfterDeclinedSwap() {
        BotMemory openerMemory = BotMemory.empty();
        Map<String, BotMemory> memories = Map.of("bot-a", openerMemory, "bot-b", BotMemory.empty());
        TradeState trade = counteredTradeOpenedByA("KATAJANOKKA",
                new TradeSelectionState(0, List.of("KAIVOPUISTO"), 0));

        BotMemory.recordTradeKilledByPartner(memories, trade, "bot-b");

        assertTrue(openerMemory.declinedSwapTargets("bot-b").contains("KATAJANOKKA"),
                "a declined property swap must block re-proposing the same target");
    }

    @Test
    void selfCancelByOpenerDoesNotDoubleCountThroughBridge() {
        // cancelAsDecline in the strategy already records self-cancels;
        // the bridge must ignore closures performed by the opener itself.
        BotMemory openerMemory = BotMemory.empty();
        Map<String, BotMemory> memories = Map.of("bot-a", openerMemory, "bot-b", BotMemory.empty());
        TradeState trade = counteredTradeOpenedByA("KATAJANOKKA",
                new TradeSelectionState(200, List.of(), 0));

        BotMemory.recordTradeKilledByPartner(memories, trade, "bot-a");

        assertEquals(0, openerMemory.declineCount("bot-b"));
        assertTrue(openerMemory.declinedSwapTargets("bot-b").isEmpty());
    }

    @Test
    void noOpOnUnknownPhase() {
        // Build a state with UNKNOWN phase via the fallback branch
        var state = TestSessionState.twoPlayerGame()
                .withPhase(TurnPhase.UNKNOWN)
                .build();
        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.NoOp.class, intent);
    }
}
