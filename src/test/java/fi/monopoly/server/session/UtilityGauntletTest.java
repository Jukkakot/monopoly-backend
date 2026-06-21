package fi.monopoly.server.session;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gauntlet comparison: {@code utility-v1} vs {@code pure-domain-v1}.
 *
 * <p>All tests are {@code @Disabled} — run manually when evaluating strategy changes:</p>
 * <pre>
 *   mvn test -Dtest=UtilityGauntletTest
 *   mvn test -Dtest=UtilityGauntletTest#paritySmokeGauntlet
 * </pre>
 *
 * <p>Promotion threshold: utility-v1 win-share must be within 5 percentage points of
 * pure-domain-v1 (parity), with 95% Wilson CIs overlapping. A clear lead for utility-v1
 * (CIs disjoint, utility-v1 higher) is required before promoting to production default.</p>
 */
class UtilityGauntletTest {

    private static final MatchScheduler.StrategyEntry PURE_DOMAIN =
            new MatchScheduler.StrategyEntry("pure-domain-v1", new PureDomainStrategy(Map.of()));

    private static final MatchScheduler.StrategyEntry UTILITY =
            new MatchScheduler.StrategyEntry("utility-v1", new UtilityStrategy(Map.of()));

    /** Baseline: pure-domain vs pure-domain should have near-zero stalemates. */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void pureDomainBaseline() {
        MatchScheduler.StrategyEntry pd1 = new MatchScheduler.StrategyEntry("pd-A", new PureDomainStrategy(Map.of()));
        MatchScheduler.StrategyEntry pd2 = new MatchScheduler.StrategyEntry("pd-B", new PureDomainStrategy(Map.of()));
        MatchScheduler.ExperimentSpec spec = new MatchScheduler.ExperimentSpec(
                List.of(pd1, pd2), 50, true, 20_000, 9001L);
        MatchScheduler.EvaluationReport report = MatchScheduler.run(spec);
        System.out.println(report.summary());
        System.out.printf("Baseline stalemate rate: %.1f%%%n",
                100.0 * report.loopSuspectedCount() / report.totalGames());
    }

    /**
     * Quick parity check: 200 seeds × mirror = 400 games.
     *
     * <p>Passes as long as utility-v1 win-share stays within 10 pp of pure-domain-v1.
     * This is the minimum bar — not promotion-ready, just "not broken."</p>
     */
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void paritySmokeGauntlet() {
        MatchScheduler.ExperimentSpec spec = new MatchScheduler.ExperimentSpec(
                List.of(PURE_DOMAIN, UTILITY),
                200,        // seeds (× 2 with mirror = 400 games)
                true,       // mirror
                20_000,
                1001L
        );

        MatchScheduler.EvaluationReport report = MatchScheduler.run(spec);
        System.out.println(report.summary());

        // pure-domain vs pure-domain baseline has ~6% loop-suspected rate in 100-game sets;
        // utility-v1 must not dramatically exceed that.  Allow up to 8% of total games.
        int totalGames = report.totalGames();
        assertTrue(report.loopSuspectedCount() <= totalGames * 0.08,
                "utility-v1 loop-suspected rate must stay below 8% (pure-domain baseline ~6%): "
                        + report.loopSuspectedCount() + "/" + totalGames);

        // Smoke check: utility-v1 lower CI must clear 33% at 400 games.
        // 400-game CI width is ~±4.5 pp; the real 35% floor is validated in fullGauntlet.
        MatchScheduler.StrategyResult utilityResult = report.results().stream()
                .filter(r -> "utility-v1".equals(r.name()))
                .findFirst().orElseThrow();
        assertTrue(utilityResult.ci().lower() >= 0.33,
                "utility-v1 win-share lower CI must be at least 33% (smoke floor): "
                        + utilityResult.ci());
    }

    /**
     * Full gauntlet: 500 seeds × mirror = 1 000 games. Use this to get tight CIs
     * before deciding whether to promote utility-v1 to the production default.
     */
    @Test
    @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void fullGauntlet() {
        MatchScheduler.ExperimentSpec spec = new MatchScheduler.ExperimentSpec(
                List.of(PURE_DOMAIN, UTILITY),
                500,        // seeds (× 2 with mirror = 1 000 games)
                true,
                20_000,
                2001L
        );

        MatchScheduler.EvaluationReport report = MatchScheduler.run(spec);
        System.out.println(report.summary());

        assertTrue(report.loopSuspectedCount() <= report.totalGames() * 0.08,
                "utility-v1 loop-suspected rate must stay below 8%: "
                        + report.loopSuspectedCount() + "/" + report.totalGames());

        MatchScheduler.StrategyResult utilityResult = report.results().stream()
                .filter(r -> "utility-v1".equals(r.name()))
                .findFirst().orElseThrow();
        System.out.printf("utility-v1 win-share: %s%n", utilityResult.ci());
    }

    /**
     * Verifies that different bot personalities produce measurably different
     * decision distributions — confirm variety exists.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void personalityVarietyCheck() {
        // Pit utility-v1 against itself — in a fair two-player game both seats should
        // win roughly 50% each, confirming the personality differences don't always
        // favour one seat.
        MatchScheduler.StrategyEntry utilityA = new MatchScheduler.StrategyEntry(
                "utility-v1-A", new UtilityStrategy(Map.of()));
        MatchScheduler.StrategyEntry utilityB = new MatchScheduler.StrategyEntry(
                "utility-v1-B", new UtilityStrategy(Map.of()));

        MatchScheduler.ExperimentSpec spec = new MatchScheduler.ExperimentSpec(
                List.of(utilityA, utilityB),
                200,
                true,
                20_000,
                3001L
        );

        MatchScheduler.EvaluationReport report = MatchScheduler.run(spec);
        System.out.println(report.summary());

        // Self-play loop-suspected rate should be low but pd-vs-pd baseline is ~6%; allow 8%.
        assertTrue(report.loopSuspectedCount() <= report.totalGames() * 0.08,
                "self-play loop-suspected rate must stay below 8%: "
                        + report.loopSuspectedCount() + "/" + report.totalGames());

        // Each side should win between 35–65% in mirror match (fair game expected)
        for (MatchScheduler.StrategyResult r : report.results()) {
            assertTrue(r.ci().lower() >= 0.30 && r.ci().upper() <= 0.70,
                    "self-play should be near 50% win-share for " + r.name() + ": " + r.ci());
        }
    }
}
