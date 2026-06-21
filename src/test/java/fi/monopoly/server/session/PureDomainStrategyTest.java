package fi.monopoly.server.session;

import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.server.bot.BotMemory;
import fi.monopoly.server.bot.Intent;
import fi.monopoly.utils.RandomSource;
import org.junit.jupiter.api.Test;

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
