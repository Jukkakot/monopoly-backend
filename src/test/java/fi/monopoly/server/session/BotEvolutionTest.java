package fi.monopoly.server.session;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Evolutionary bot optimizer.
 *
 * <p>Not run in CI ({@code @Disabled}). Run manually:
 * <pre>
 *   mvn test -Dtest=BotEvolutionTest#evolveOptimalConfig -pl . -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>Prints per-generation standings and a final diff of the best config vs defaults.
 * Update {@link StrongBotConfig#defaults()} based on the output.</p>
 */
@Disabled("Long-running evolutionary test, run manually when needed")
class BotEvolutionTest {

    @BeforeAll
    static void suppressGameLogs() {
        // Suppress per-step game logs so tournament output is readable
        for (String pkg : new String[]{
                "fi.monopoly.application.session.turn",
                "fi.monopoly.application.session.auction",
                "fi.monopoly.application.session.debt",
                "fi.monopoly.domain.session",
                "fi.monopoly.application.session"}) {
            ((Logger) LoggerFactory.getLogger(pkg)).setLevel(Level.ERROR);
        }
    }

    /** Full evolutionary run — takes ~8-12 minutes. */
    @Test
    void evolveOptimalConfig() {
        int populationSize = 12;
        int generations    = 15;
        int gamesPerPair   = 20;
        long seedBase      = 77777L;

        System.out.println("Starting evolution: pop=" + populationSize
                + " gens=" + generations + " games/pair=" + gamesPerPair);
        System.out.println("Estimated time: ~"
                + (populationSize * (populationSize - 1) / 2 * gamesPerPair * generations / 10) + "s");

        BotTournament.Entry best = BotTournament.evolve(
                populationSize, generations, gamesPerPair, seedBase, true);

        System.out.println("\n" + "=".repeat(70));
        System.out.println("BEST CONFIG FOUND: " + best.name());
        System.out.println("=".repeat(70));
        printDiff(best.config(), StrongBotConfig.defaults());

        // Verify best beats defaults in a larger head-to-head
        System.out.println("\nVerification: best vs defaults (50 games each direction)");
        List<BotTournament.Standing> verify = BotTournament.roundRobin(List.of(
                new BotTournament.Entry("best",     best.config()),
                new BotTournament.Entry("defaults", StrongBotConfig.defaults()),
                new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                new BotTournament.Entry("cautious",   StrongBotConfig.cautious())
        ), 50, seedBase + 999_999L);
        BotTournament.printStandings(verify);
    }

    /**
     * Quick ablation: verify individual parameter changes matter.
     * Runs in ~2 minutes.
     */
    @Test
    void ablationStudy() {
        long seed = 42424242L;
        int games = 30;
        StrongBotConfig base = StrongBotConfig.defaults();

        List<BotTournament.Entry> configs = new ArrayList<>();
        configs.add(new BotTournament.Entry("defaults", base));

        // Vary each of the 12 tunable parameters ±25%
        String[] paramNames = {
                "buyThreshold+25%", "buyThreshold-25%",
                "minCash+25%", "minCash-25%",
                "dangerCash+25%", "dangerCash-25%",
                "buildAggression+25%", "buildAggression-25%",
                "hotelAversion+25%", "hotelAversion-25%",
                "liquidityPenalty+25%", "liquidityPenalty-25%",
                "blockWeight+25%", "blockWeight-25%",
                "railroadWeight+25%", "railroadWeight-25%",
                "utilityWeight+25%", "utilityWeight-25%",
                "auctionAggression+25%", "auctionAggression-25%",
                "tradeTolerance+25%", "tradeTolerance-25%",
                "buildReservePerMonopoly+25%", "buildReservePerMonopoly-25%"
        };
        StrongBotConfig[] variants = {
                base.toBuilder().buyThreshold(base.buyThreshold() * 1.25).build(),
                base.toBuilder().buyThreshold(base.buyThreshold() * 0.75).build(),
                base.toBuilder().minCashReserve((int)(base.minCashReserve() * 1.25)).build(),
                base.toBuilder().minCashReserve((int)(base.minCashReserve() * 0.75)).build(),
                base.toBuilder().dangerCashReserve((int)(base.dangerCashReserve() * 1.25)).build(),
                base.toBuilder().dangerCashReserve((int)(base.dangerCashReserve() * 0.75)).build(),
                base.toBuilder().houseBuildAggression(base.houseBuildAggression() * 1.25).build(),
                base.toBuilder().houseBuildAggression(base.houseBuildAggression() * 0.75).build(),
                base.toBuilder().hotelAversion(base.hotelAversion() * 1.25).build(),
                base.toBuilder().hotelAversion(base.hotelAversion() * 0.75).build(),
                base.toBuilder().liquidityPenaltyWeight(base.liquidityPenaltyWeight() * 1.25).build(),
                base.toBuilder().liquidityPenaltyWeight(base.liquidityPenaltyWeight() * 0.75).build(),
                base.toBuilder().opponentBlockWeight(base.opponentBlockWeight() * 1.25).build(),
                base.toBuilder().opponentBlockWeight(base.opponentBlockWeight() * 0.75).build(),
                base.toBuilder().railroadWeight(base.railroadWeight() * 1.25).build(),
                base.toBuilder().railroadWeight(base.railroadWeight() * 0.75).build(),
                base.toBuilder().utilityWeight(base.utilityWeight() * 1.25).build(),
                base.toBuilder().utilityWeight(base.utilityWeight() * 0.75).build(),
                base.toBuilder().auctionAggression(base.auctionAggression() * 1.25).build(),
                base.toBuilder().auctionAggression(base.auctionAggression() * 0.75).build(),
                base.toBuilder().tradeFairnessTolerance((int)(base.tradeFairnessTolerance() * 1.25)).build(),
                base.toBuilder().tradeFairnessTolerance((int)(base.tradeFairnessTolerance() * 0.75)).build(),
                base.toBuilder().buildReservePerOpponentMonopoly((int)(base.buildReservePerOpponentMonopoly() * 1.25)).build(),
                base.toBuilder().buildReservePerOpponentMonopoly((int)(base.buildReservePerOpponentMonopoly() * 0.75)).build(),
        };
        for (int k = 0; k < paramNames.length; k++) {
            configs.add(new BotTournament.Entry(paramNames[k], variants[k]));
        }

        System.out.println("Ablation study: " + configs.size() + " configs × " + games + " games/pair");
        List<BotTournament.Standing> standings = BotTournament.roundRobin(configs, games, seed);
        BotTournament.printStandings(standings);

        System.out.println("\nParams that beat defaults:");
        double defaultWin = standings.stream()
                .filter(s -> "defaults".equals(s.name()))
                .findFirst().map(BotTournament.Standing::winRate).orElse(0.0);
        standings.stream()
                .filter(s -> s.winRate() > defaultWin && !"defaults".equals(s.name()))
                .sorted(Comparator.comparingDouble(BotTournament.Standing::winRate).reversed())
                .forEach(s -> System.out.printf("  %-35s  win%%=%.1f  (+%.1f%%)%n",
                        s.name(), s.winRate() * 100, (s.winRate() - defaultWin) * 100));
    }

    /**
     * Quick sanity: current defaults vs aggressive vs cautious.
     * Runs in ~30 seconds.
     */
    @Test
    void quickBenchmark() {
        List<BotTournament.Standing> standings = BotTournament.roundRobin(List.of(
                new BotTournament.Entry("defaults",   StrongBotConfig.defaults()),
                new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                new BotTournament.Entry("cautious",   StrongBotConfig.cautious())
        ), 50, 12345L);
        BotTournament.printStandings(standings);
    }

    // -------------------------------------------------------------------------

    private static void printDiff(StrongBotConfig best, StrongBotConfig def) {
        record P(String name, double bestV, double defV) {}
        List<P> params = List.of(
                new P("buyThreshold",                  best.buyThreshold(),                  def.buyThreshold()),
                new P("minCashReserve",                best.minCashReserve(),                def.minCashReserve()),
                new P("dangerCashReserve",             best.dangerCashReserve(),             def.dangerCashReserve()),
                new P("houseBuildAggression",          best.houseBuildAggression(),          def.houseBuildAggression()),
                new P("hotelAversion",                 best.hotelAversion(),                 def.hotelAversion()),
                new P("liquidityPenaltyWeight",        best.liquidityPenaltyWeight(),        def.liquidityPenaltyWeight()),
                new P("opponentBlockWeight",           best.opponentBlockWeight(),            def.opponentBlockWeight()),
                new P("railroadWeight",                best.railroadWeight(),                def.railroadWeight()),
                new P("utilityWeight",                 best.utilityWeight(),                 def.utilityWeight()),
                new P("auctionAggression",             best.auctionAggression(),             def.auctionAggression()),
                new P("tradeFairnessTolerance",        best.tradeFairnessTolerance(),        def.tradeFairnessTolerance()),
                new P("buildReservePerOpponentMonopoly", best.buildReservePerOpponentMonopoly(), def.buildReservePerOpponentMonopoly()),
                new P("progressWeight",                best.progressWeight(),                def.progressWeight()),
                new P("completionWeight",              best.completionWeight(),              def.completionWeight()),
                new P("developmentBias",               best.developmentBias(),               def.developmentBias()),
                new P("mortgageTolerance",             best.mortgageTolerance(),             def.mortgageTolerance()),
                new P("unmortgageAggression",          best.unmortgageAggression(),          def.unmortgageAggression()),
                new P("postMonopolyCashBuffer",        best.postMonopolyCashBuffer(),        def.postMonopolyCashBuffer()),
                new P("auctionSetCompletionBonus",     best.auctionSetCompletionBonus(),     def.auctionSetCompletionBonus())
        );

        System.out.printf("%-35s  %-12s  %-12s  %s%n", "Parameter", "Best", "Default", "Change");
        System.out.println("-".repeat(75));
        for (P p : params) {
            double delta = p.bestV() - p.defV();
            double pct = p.defV() != 0 ? delta / Math.abs(p.defV()) * 100 : 0;
            String marker = Math.abs(pct) > 5 ? (pct > 0 ? "  ▲" : "  ▼") : "";
            System.out.printf("%-35s  %-12.2f  %-12.2f  %+.1f%%%s%n",
                    p.name(), p.bestV(), p.defV(), pct, marker);
        }
    }
}
