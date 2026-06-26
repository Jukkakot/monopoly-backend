package fi.monopoly.server.session;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * A/B benchmark for the {@code tradeSaleAggression} knob — how steep a premium the bot demands when
 * selling a deed. Each candidate value is matched head-to-head (2-player mirror) against the legacy
 * margin (aggression 1.0) on the production {@code aggressive()} preset. The Wilson 95% CI says
 * whether a sharper sale margin actually wins more games or just kills beneficial trades.
 *
 * <p>Manual benchmark — invoke explicitly:
 * {@code mvn test -Dtest=TradeSaleAggressionBenchmarkTest#sweep}.
 */
final class TradeSaleAggressionBenchmarkTest {

    @Test
    @Disabled("Manual benchmark — sweeps several candidate values, ~800 games each. "
            + "Last result: 1.4/1.8/2.4 all statistically tied with 1.0 (~36% each, CIs overlap).")
    void sweep() {
        for (double candidate : new double[]{1.4, 1.8, 2.4}) {
            StrongBotConfig sharp  = StrongBotConfig.aggressive().toBuilder().tradeSaleAggression(candidate).build();
            StrongBotConfig legacy = StrongBotConfig.aggressive().toBuilder().tradeSaleAggression(1.0).build();

            var spec = new MatchScheduler.ExperimentSpec(
                    List.of(
                            new MatchScheduler.StrategyEntry("sale-" + candidate, new PureDomainStrategy(sharp)),
                            new MatchScheduler.StrategyEntry("sale-1.0",          new PureDomainStrategy(legacy))
                    ),
                    400, true, 20_000, 11_000L);

            System.out.println("\n##### tradeSaleAggression = " + candidate + " vs 1.0 #####");
            System.out.println(MatchScheduler.run(spec).summary());
        }
    }
}
