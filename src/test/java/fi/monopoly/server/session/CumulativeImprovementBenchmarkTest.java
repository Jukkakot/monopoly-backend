package fi.monopoly.server.session;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Capstone A/B benchmark: does the current shipped bot beat the bot from the start of this
 * improvement arc? Both arms use the production {@code aggressive()} preset; the "before" arm
 * disables this session's two strategy levers (mortgage-to-build and sharper sale pricing).
 *
 * <p>Also re-checks that {@code aggressive()} still out-performs {@code defaults()} in 2-player
 * after the strategy-code changes (preset-ordering sanity).
 *
 * <p>Manual benchmark — invoke explicitly:
 * {@code mvn test -Dtest=CumulativeImprovementBenchmarkTest#afterVsBefore}.
 */
final class CumulativeImprovementBenchmarkTest {

    @Test
    @Disabled("Manual benchmark — ~2000 games. Last result: after 41.9% [38.9,45.0] vs before "
            + "35.3% [32.4,38.3] (+6.6pp, CIs disjoint); aggressive 41.0% > defaults 33.0% (ordering intact).")
    void afterVsBefore() {
        StrongBotConfig after  = StrongBotConfig.aggressive(); // mortgageToBuild=true, tradeSaleAggression=1.4
        StrongBotConfig before = StrongBotConfig.aggressive().toBuilder()
                .mortgageToBuild(false)
                .tradeSaleAggression(1.0)
                .build();

        var spec = new MatchScheduler.ExperimentSpec(
                List.of(
                        new MatchScheduler.StrategyEntry("after",  new PureDomainStrategy(after)),
                        new MatchScheduler.StrategyEntry("before", new PureDomainStrategy(before))
                ),
                500, true, 20_000, 21_000L);
        System.out.println("\n##### shipped bot (after) vs start-of-session (before) #####");
        System.out.println(MatchScheduler.run(spec).summary());

        var ordering = new MatchScheduler.ExperimentSpec(
                List.of(
                        new MatchScheduler.StrategyEntry("aggressive", new PureDomainStrategy(StrongBotConfig.aggressive())),
                        new MatchScheduler.StrategyEntry("defaults",   new PureDomainStrategy(StrongBotConfig.defaults()))
                ),
                400, true, 20_000, 22_000L);
        System.out.println("\n##### preset ordering: aggressive vs defaults (2p) #####");
        System.out.println(MatchScheduler.run(ordering).summary());
    }
}
