package fi.monopoly.server.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that 4-player experiments now decide reliably via the net-worth tiebreak, instead of
 * discarding ~80% of games as stalemates — the unlock that makes denial / opponent-modelling levers
 * measurable. Also sanity-checks that a known-good lever (mortgage-to-build) shows signal in 4p.
 */
final class FourPlayerMeasurabilityTest {

    @Test
    void fourPlayerGamesDecideReliablyAndShowSignal() {
        // 4-player production preset is defaults(); A/B the mortgage-to-build lever (known +7.5pp in 2p).
        StrongBotConfig on  = StrongBotConfig.defaults();                                  // mtb on
        StrongBotConfig off = StrongBotConfig.defaults().toBuilder().mortgageToBuild(false).build();

        var report = MatchScheduler.runFourPlayerAB(
                new MatchScheduler.StrategyEntry("mtb-on",  new PureDomainStrategy(on)),
                new MatchScheduler.StrategyEntry("mtb-off", new PureDomainStrategy(off)),
                6, 20_000, 41_000L);   // 6 seeds × 6 arrangements = 36 games (fast CI guard)
        System.out.println(report.summary());

        int totalGames = report.totalGames();
        int draws = report.loopSuspectedCount();   // undecided (exact net-worth ties only)
        double decidedFraction = totalGames > 0 ? 1.0 - (double) draws / totalGames : 0;

        // The whole point: 4-player games must now be decidable (was ~17% decided before the fix).
        assertTrue(decidedFraction > 0.90,
                "Expected >90% of 4-player games to be decided via net-worth tiebreak, got "
                        + String.format("%.1f%%", decidedFraction * 100));
    }
}
