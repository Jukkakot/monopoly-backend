package fi.monopoly.server.session;

import fi.monopoly.server.bot.BotMemory;
import fi.monopoly.server.bot.BotStrategy;
import fi.monopoly.server.bot.Intent;
import fi.monopoly.utils.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2: smoke tests for {@link HeadlessGameRunner} and {@link MatchScheduler}.
 *
 * <p>These are fast unit-level checks: same-seed determinism, no crash on a short run,
 * and basic MatchScheduler wiring. They do NOT run full tournament-scale games.</p>
 */
class HeadlessGameRunnerTest {

    /** PureDomainStrategy used as the reference strategy under test. */
    private static final BotStrategy STRATEGY = new PureDomainStrategy(Map.of());

    @Test
    void singleGameCompletes() {
        var config = new HeadlessGameRunner.MatchConfig(
                42L,
                List.of(new HeadlessGameRunner.SeatAssignment(STRATEGY),
                        new HeadlessGameRunner.SeatAssignment(STRATEGY)),
                20_000);
        HeadlessGameRunner.GameResult result = HeadlessGameRunner.play(config);
        assertNotNull(result);
        assertTrue(result.steps() > 0, "game must advance at least one step");
        // Either a winner or stalemate — both are valid
        assertTrue(result.winnerSeat() >= -1 && result.winnerSeat() <= 1);
    }

    @Test
    void sameSeedProducesIdenticalResult() {
        var config = new HeadlessGameRunner.MatchConfig(
                99L,
                List.of(new HeadlessGameRunner.SeatAssignment(STRATEGY),
                        new HeadlessGameRunner.SeatAssignment(STRATEGY)),
                20_000);
        HeadlessGameRunner.GameResult r1 = HeadlessGameRunner.play(config);
        HeadlessGameRunner.GameResult r2 = HeadlessGameRunner.play(config);
        assertEquals(r1.winnerSeat(), r2.winnerSeat(), "same seed → same winner");
        assertEquals(r1.steps(),      r2.steps(),      "same seed → same step count");
        assertEquals(r1.outcome(),    r2.outcome(),     "same seed → same outcome");
    }

    @Test
    void differentSeedsMayProduceDifferentResults() {
        // With a deterministic strategy two different seeds should occasionally differ.
        // Run a few seeds and verify at least one varies — confirms RNG is wired.
        int diffCount = 0;
        for (int s = 0; s < 8; s++) {
            var c1 = new HeadlessGameRunner.MatchConfig(s,
                    List.of(new HeadlessGameRunner.SeatAssignment(STRATEGY),
                            new HeadlessGameRunner.SeatAssignment(STRATEGY)), 20_000);
            var c2 = new HeadlessGameRunner.MatchConfig(s + 100,
                    List.of(new HeadlessGameRunner.SeatAssignment(STRATEGY),
                            new HeadlessGameRunner.SeatAssignment(STRATEGY)), 20_000);
            HeadlessGameRunner.GameResult r1 = HeadlessGameRunner.play(c1);
            HeadlessGameRunner.GameResult r2 = HeadlessGameRunner.play(c2);
            if (r1.steps() != r2.steps() || r1.winnerSeat() != r2.winnerSeat()) diffCount++;
        }
        assertTrue(diffCount > 0, "at least one seed pair should differ");
    }

    @Test
    void noOpStrategyTriggersStalemate() {
        // A strategy that always returns NoOp should hit the step/reject cap quickly.
        BotStrategy noOpStrategy = new BotStrategy() {
            @Override public Intent decide(fi.monopoly.domain.session.SessionState state,
                                           String botId, BotMemory memory, RandomSource rng) {
                return new Intent.NoOp();
            }
            @Override public String name() { return "no-op"; }
        };
        var config = new HeadlessGameRunner.MatchConfig(
                1L,
                List.of(new HeadlessGameRunner.SeatAssignment(noOpStrategy),
                        new HeadlessGameRunner.SeatAssignment(noOpStrategy)),
                50);  // tiny cap so test finishes fast
        HeadlessGameRunner.GameResult result = HeadlessGameRunner.play(config);
        assertEquals(HeadlessGameRunner.Outcome.STALEMATE, result.outcome());
    }

    @Test
    void matchSchedulerRunsWithoutError() {
        // Minimal duel — 4 games (2 seeds × mirror) to verify wiring, not statistics.
        var spec = new MatchScheduler.ExperimentSpec(
                List.of(new MatchScheduler.StrategyEntry("A", STRATEGY),
                        new MatchScheduler.StrategyEntry("B", STRATEGY)),
                2, true, 20_000, 77L);
        MatchScheduler.EvaluationReport report = MatchScheduler.run(spec);
        assertNotNull(report);
        assertEquals(2, report.results().size());
        assertEquals(4, report.totalGames());  // 2 seeds × 2 (mirror)
        // Both strategies get 4 games each (every game counts for both)
        assertEquals(4, report.results().get(0).games());
        assertEquals(4, report.results().get(1).games());
        // Wins + stalemates + draws must account for all games
        int totalWins = report.results().stream().mapToInt(MatchScheduler.StrategyResult::wins).sum();
        assertTrue(totalWins <= report.totalGames(),
                "total wins must be ≤ total games (stalemates leave no winner)");
    }

    @Test
    void wilsonIntervalForAllWins() {
        MatchScheduler.WilsonInterval ci = MatchScheduler.WilsonInterval.of(100, 100);
        assertEquals(1.0, ci.winShare(), 0.001);
        assertTrue(ci.lower() > 0.9, "100% wins should have CI above 90%");
        assertTrue(ci.upper() <= 1.0);
    }

    @Test
    void wilsonIntervalForNoWins() {
        MatchScheduler.WilsonInterval ci = MatchScheduler.WilsonInterval.of(0, 100);
        assertEquals(0.0, ci.winShare(), 0.001);
        assertTrue(ci.upper() < 0.1, "0 wins should have CI below 10%");
        assertTrue(ci.lower() >= 0.0);
    }
}
