package fi.monopoly.server.session;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Head-to-head A/B benchmark for the mortgage-to-build lever ({@code mortgageToBuild}).
 *
 * <p>Uses {@link MatchScheduler} 2-player mirror matching against the {@code aggressive()} preset
 * (the production 2-player config). Both arms are identical except {@code mortgageToBuild}
 * (on vs off), so the Wilson 95% CI isolates the lever's win-rate effect.
 *
 * <p>Manual benchmark — kept out of the CI run; invoke explicitly:
 * {@code mvn test -Dtest=MortgageToBuildBenchmarkTest#onVsOff}.
 */
final class MortgageToBuildBenchmarkTest {

    @Test
    @Disabled("Manual benchmark — 1000 games, ~2 min. Run explicitly to re-measure the lever. "
            + "Last result: mtb-on 40.7% [37.7,43.8] vs mtb-off 33.2% [30.4,36.2] — on is better (CIs disjoint).")
    void onVsOff() {
        StrongBotConfig on  = StrongBotConfig.aggressive().toBuilder().mortgageToBuild(true).build();
        StrongBotConfig off = StrongBotConfig.aggressive().toBuilder().mortgageToBuild(false).build();

        var spec = new MatchScheduler.ExperimentSpec(
                List.of(
                        new MatchScheduler.StrategyEntry("mtb-on",  new PureDomainStrategy(on)),
                        new MatchScheduler.StrategyEntry("mtb-off", new PureDomainStrategy(off))
                ),
                500,     // gamesPerPair (×2 for mirror = 1000 games)
                true,    // mirror
                20_000,  // maxSteps
                7_000L   // seedBase
        );

        MatchScheduler.EvaluationReport report = MatchScheduler.run(spec);
        System.out.println(report.summary());
    }
}
