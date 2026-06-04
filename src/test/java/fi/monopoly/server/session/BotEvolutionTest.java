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

    /**
     * Evolutionary search for 2–3 player optimal config.
     * Uses full pair round-robin per generation.
     * Takes ~8-12 minutes with default params.
     */
    @Test
    @Disabled("Long — run manually to find optimal 2-3 player config (~10 min)")
    void evolveSmallGame() {
        int pop  = 12, gens = 15, games = 20;
        long seed = 77777L;

        System.out.printf("Evolving 2-player config: pop=%d gens=%d games/pair=%d%n", pop, gens, games);
        BotTournament.Entry best = BotTournament.evolve(pop, gens, games, 2,
                List.of(new BotTournament.Entry("defaults",   StrongBotConfig.defaults()),
                        new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                        new BotTournament.Entry("cautious",   StrongBotConfig.cautious())),
                seed, true);

        System.out.println("\n" + "=".repeat(70));
        System.out.println("BEST 2-PLAYER CONFIG: " + best.name());
        printDiff(best.config(), StrongBotConfig.defaults());

        // Verify against known presets
        List<BotTournament.Standing> verify = BotTournament.roundRobin(List.of(
                new BotTournament.Entry("best",       best.config()),
                new BotTournament.Entry("defaults",   StrongBotConfig.defaults()),
                new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                new BotTournament.Entry("cautious",   StrongBotConfig.cautious())
        ), 50, seed + 999_999L);
        BotTournament.printStandings(verify);
    }

    /**
     * Evolutionary search for 4–6 player optimal config.
     * Uses sampled N-player groups per generation.
     * Takes ~5-10 minutes with default params.
     */
    @Test
    @Disabled("Long — run manually to find optimal 4-6 player config")
    void evolveLargeGame() {
        int pop  = 12, gens = 15;
        int gamesPerGen = 200;  // total sampled games per generation
        int playerCount = 4;    // players per game (try 4, 5, or 6)
        long seed = 88888L;

        System.out.printf("Evolving %d-player config: pop=%d gens=%d games/gen=%d%n",
                playerCount, pop, gens, gamesPerGen);
        BotTournament.Entry best = BotTournament.evolve(pop, gens, gamesPerGen, playerCount,
                List.of(new BotTournament.Entry("sixPlayer",  StrongBotConfig.sixPlayer()),
                        new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                        new BotTournament.Entry("defaults",   StrongBotConfig.defaults())),
                seed, true);

        System.out.println("\n" + "=".repeat(70));
        System.out.printf("BEST %d-PLAYER CONFIG: %s%n", playerCount, best.name());
        printDiff(best.config(), StrongBotConfig.sixPlayer());

        // Verify in a freeForAll benchmark
        List<BotTournament.Standing> verify = BotTournament.sampledTournament(List.of(
                new BotTournament.Entry("best",      best.config()),
                new BotTournament.Entry("sixPlayer", StrongBotConfig.sixPlayer()),
                new BotTournament.Entry("aggressive",StrongBotConfig.aggressive()),
                new BotTournament.Entry("defaults",  StrongBotConfig.defaults()),
                new BotTournament.Entry("cautious",  StrongBotConfig.cautious())
        ), 300, playerCount, seed + 999_999L);
        BotTournament.printStandings(verify);
    }

    /**
     * Quick ablation: verify individual parameter changes matter.
     * Runs in ~2 minutes.
     */
    @Test
    @Disabled("Long — run manually for parameter sensitivity analysis")
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
     * Quick sanity: current defaults vs aggressive vs cautious vs sixPlayer (2-player, 3-player, 4-player).
     * Runs in ~90 seconds total.
     */
    @Test
    @Disabled("Run manually to compare presets across player counts (~90s)")
    void quickBenchmark() {
        List<BotTournament.Entry> configs = List.of(
                new BotTournament.Entry("defaults",   StrongBotConfig.defaults()),
                new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                new BotTournament.Entry("cautious",   StrongBotConfig.cautious()),
                new BotTournament.Entry("sixPlayer",  StrongBotConfig.sixPlayer())
        );

        System.out.println("--- 2-player round-robin (50 games/pair) ---");
        BotTournament.printStandings(BotTournament.roundRobin(configs, 50, 12345L));

        System.out.println("--- 3-player sampled (300 games) ---");
        BotTournament.printStandings(BotTournament.sampledTournament(configs, 300, 3, 23456L));

        System.out.println("--- 4-player sampled (300 games) ---");
        BotTournament.printStandings(BotTournament.sampledTournament(configs, 300, 4, 34567L));

        System.out.println("--- 6-player sampled (300 games) ---");
        BotTournament.printStandings(BotTournament.sampledTournament(configs, 300, 6, 45678L));
    }

    /**
     * 6-player free-for-all: defaults, aggressive, cautious, sixPlayer × 2.
     * Tests whether games actually end (bankruptcies) and which config wins.
     * Runs in ~2-3 minutes.
     */
    @Test
    @Disabled("Run manually for 6-player analysis (~3 min)")
    void sixPlayerBenchmark() {
        List<BotTournament.Entry> configs = List.of(
                new BotTournament.Entry("sixPlayer-A", StrongBotConfig.sixPlayer()),
                new BotTournament.Entry("sixPlayer-B", StrongBotConfig.sixPlayer()),
                new BotTournament.Entry("aggressive",  StrongBotConfig.aggressive()),
                new BotTournament.Entry("defaults",    StrongBotConfig.defaults()),
                new BotTournament.Entry("cautious",    StrongBotConfig.cautious()),
                new BotTournament.Entry("sixPlayer-C", StrongBotConfig.sixPlayer())
        );

        System.out.println("=== 6-player free-for-all (100 games) ===");
        List<BotTournament.Standing> standings = BotTournament.freeForAll(configs, 100, 55555L);
        BotTournament.printStandings(standings);

        long bankruptcyGames = standings.stream().mapToLong(s -> s.wins() + s.losses()).sum() / (configs.size() - 1);
        double avgSteps = standings.stream().mapToDouble(BotTournament.Standing::avgSteps).average().orElse(0);
        System.out.printf("%nGames decided by bankruptcy: %d/100  avg steps per game: %.0f%n",
                bankruptcyGames, avgSteps);
    }

    /**
     * 6-player ablation: which parameters matter most in 6-player games.
     * Runs in ~5 minutes.
     */
    @Test
    @Disabled("Run manually for 6-player ablation (~5 min)")
    void sixPlayerAblation() {
        StrongBotConfig base = StrongBotConfig.sixPlayer();
        List<BotTournament.Entry> configs = new ArrayList<>();
        configs.add(new BotTournament.Entry("sixPlayer-base", base));

        String[] names = {
                "buyThreshold+25%", "buyThreshold-25%",
                "hotelAversion+25%", "hotelAversion-25%",
                "buildAggression+25%", "buildAggression-25%",
                "dangerCash+25%", "dangerCash-25%",
                "tradeTolerance+50%", "tradeTolerance-50%",
                "liquidityPenalty+25%", "liquidityPenalty-25%",
        };
        StrongBotConfig[] variants = {
                base.toBuilder().buyThreshold(base.buyThreshold() * 1.25).build(),
                base.toBuilder().buyThreshold(base.buyThreshold() * 0.75).build(),
                base.toBuilder().hotelAversion(base.hotelAversion() * 1.25).build(),
                base.toBuilder().hotelAversion(base.hotelAversion() * 0.75).build(),
                base.toBuilder().houseBuildAggression(base.houseBuildAggression() * 1.25).build(),
                base.toBuilder().houseBuildAggression(base.houseBuildAggression() * 0.75).build(),
                base.toBuilder().dangerCashReserve((int)(base.dangerCashReserve() * 1.25)).build(),
                base.toBuilder().dangerCashReserve((int)(base.dangerCashReserve() * 0.75)).build(),
                base.toBuilder().tradeFairnessTolerance((int)(base.tradeFairnessTolerance() * 1.5)).build(),
                base.toBuilder().tradeFairnessTolerance((int)(base.tradeFairnessTolerance() * 0.5)).build(),
                base.toBuilder().liquidityPenaltyWeight(base.liquidityPenaltyWeight() * 1.25).build(),
                base.toBuilder().liquidityPenaltyWeight(base.liquidityPenaltyWeight() * 0.75).build(),
        };
        for (int k = 0; k < names.length; k++) {
            configs.add(new BotTournament.Entry(names[k], variants[k]));
        }

        System.out.println("=== 6-player ablation (" + configs.size() + " configs) ===");
        // Use freeForAll with all configs to simulate real 6-player dynamics
        // Run round-robin between base and each variant in 3-player games
        List<BotTournament.Standing> standings = BotTournament.roundRobin(configs, 20, 66666L);
        BotTournament.printStandings(standings);

        double baseWin = standings.stream()
                .filter(s -> "sixPlayer-base".equals(s.name()))
                .findFirst().map(BotTournament.Standing::winRate).orElse(0.0);
        System.out.println("\nParams that beat base in round-robin:");
        standings.stream()
                .filter(s -> s.winRate() > baseWin && !"sixPlayer-base".equals(s.name()))
                .sorted(Comparator.comparingDouble(BotTournament.Standing::winRate).reversed())
                .forEach(s -> System.out.printf("  %-30s  %.1f%%  (+%.1f%%)%n",
                        s.name(), s.winRate() * 100, (s.winRate() - baseWin) * 100));
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
