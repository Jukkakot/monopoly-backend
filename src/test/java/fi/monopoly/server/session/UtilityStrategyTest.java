package fi.monopoly.server.session;

import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.server.bot.BotMemory;
import fi.monopoly.server.bot.Intent;
import fi.monopoly.utils.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for {@link UtilityStrategy} — buy, build, unmortgage, and delegation.
 */
class UtilityStrategyTest {

    private final UtilityStrategy strategy = new UtilityStrategy(Map.of());
    private final RandomSource rng = RandomSource.seeded(42L);

    @Test
    void delegatesToPureDomainOnWaitingForRoll() {
        var state = TestSessionState.twoPlayerGame()
                .withPhase(TurnPhase.WAITING_FOR_ROLL)
                .build();
        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.Roll.class, intent, "should delegate roll to PureDomainStrategy");
    }

    @Test
    void delegatesToPureDomainOnEndTurn() {
        var state = TestSessionState.twoPlayerGame()
                .withPhase(TurnPhase.WAITING_FOR_END_TURN)
                .build();
        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.EndTurn.class, intent, "should delegate end-turn to PureDomainStrategy");
    }

    @Test
    void delegatesToPureDomainOnCardAck() {
        var state = TestSessionState.twoPlayerGame()
                .withPhase(TurnPhase.WAITING_FOR_CARD_ACK)
                .build();
        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.AcknowledgeCard.class, intent);
    }

    @Test
    void nameIsUtilityV1() {
        assertEquals("utility-v1", strategy.name());
    }

    @Test
    void sameSeedProducesSameIntent() {
        var state = TestSessionState.twoPlayerGame()
                .withPhase(TurnPhase.WAITING_FOR_ROLL)
                .build();
        Intent a = strategy.decide(state, "player-1", BotMemory.empty(), RandomSource.seeded(7L));
        Intent b = strategy.decide(state, "player-1", BotMemory.empty(), RandomSource.seeded(7L));
        assertEquals(a.getClass(), b.getClass(), "same seed must yield same intent class");
    }

    @Test
    void unmortgagesWhenOwnsFullGroupAndHasCash() {
        // player-1 owns both brown properties (B1, B2); B1 is mortgaged.
        // With 1 500 € cash and a complete group, utility score should beat the baseline.
        var state = TestSessionState.twoPlayerGame()
                .withOwnership("player-1", "B1", "B2")
                .withMortgaged("B1")
                .withPhase(TurnPhase.WAITING_FOR_END_TURN)
                .build();
        Intent intent = strategy.decide(state, "player-1", BotMemory.empty(), rng);
        assertInstanceOf(Intent.Unmortgage.class, intent,
                "should choose to unmortgage the mortgaged property in a complete group");
    }

    @Test
    void gameSimulationCompletesWithUtilityStrategy() {
        // Smoke test: a full game with UtilityStrategy should terminate without exception.
        var config = new HeadlessGameRunner.MatchConfig(
                11L,
                java.util.List.of(
                        new HeadlessGameRunner.SeatAssignment(strategy),
                        new HeadlessGameRunner.SeatAssignment(strategy)),
                20_000);
        HeadlessGameRunner.GameResult result = HeadlessGameRunner.play(config);
        assertNotNull(result);
        // Loop-suspected stalemates are OK as long as there's no exception
        assertFalse(result.steps() == 0, "game should advance at least one step");
    }
}
