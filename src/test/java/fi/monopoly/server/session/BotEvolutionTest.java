package fi.monopoly.server.session;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Evolutionary bot optimizer — run manually to tune {@link StrongBotConfig}.
 *
 * <h3>How to run</h3>
 * <pre>
 *   mvn test -Dtest=BotEvolutionTest#quickBenchmark            # ~90s  — sanity / preset comparison
 *   mvn test -Dtest=BotEvolutionTest#ablationStudy             # ~4min — which params matter most?
 *   mvn test -Dtest=BotEvolutionTest#evolveSmallGame           # ~8min — find optimal 2-player config
 *   mvn test -Dtest=BotEvolutionTest#evolveLargeGame           # ~10min — find optimal 4-player config
 *   mvn test -Dtest=BotEvolutionTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <h3>Output design — incremental printing</h3>
 * <p>Every test prints results as soon as each piece of work finishes, so you can
 * watch the numbers arrive and terminate early if you see a clear winner.
 * {@code roundRobin()} and {@code sampledTournament()} both use parallel streams
 * internally, so each generation/batch runs fast even as the outer loop is sequential.</p>
 *
 * <h3>Workflow: improving a preset</h3>
 * <ol>
 *   <li>Run {@code ablationStudy()} to see which parameters have the biggest impact.</li>
 *   <li>Run {@code evolveSmallGame()} or {@code evolveLargeGame()} to get an optimised config.</li>
 *   <li>Copy-paste the Builder snippet from the final diff into {@link StrongBotConfig#defaults()}
 *       (or the relevant preset).</li>
 *   <li>Run {@code quickBenchmark()} to verify the new preset beats the old one.</li>
 * </ol>
 */
class BotEvolutionTest {

    @BeforeAll
    static void suppressGameLogs() {
        // Suppress per-step game noise so tournament output is readable
        for (String pkg : new String[]{
                "fi.monopoly.application.session.turn",
                "fi.monopoly.application.session.auction",
                "fi.monopoly.application.session.debt",
                "fi.monopoly.domain.session",
                "fi.monopoly.application.session"}) {
            ((Logger) LoggerFactory.getLogger(pkg)).setLevel(Level.ERROR);
        }
    }

    // =========================================================================
    // Quick sanity — preset comparison across player counts (~90 s)
    // =========================================================================

    /**
     * Compares all named presets in 2/3/4/6-player games.
     * Run this after changing any preset to make sure you didn't regress.
     * Each bracket prints as soon as its games finish.
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

        // Each bracket is printed as soon as its games finish
        printBracket("2-player round-robin (100 games/pair)",
                BotTournament.roundRobin(configs, 100, 12345L));
        printBracket("3-player sampled (400 games)",
                BotTournament.sampledTournament(configs, 400, 3, 23456L));
        printBracket("4-player sampled (400 games)",
                BotTournament.sampledTournament(configs, 400, 4, 34567L));
        printBracket("6-player sampled (400 games)",
                BotTournament.sampledTournament(configs, 400, 6, 45678L));
    }

    // =========================================================================
    // Ablation study — which parameters matter most? (~4 min, incremental output)
    // =========================================================================

    /**
     * Head-to-head sensitivity analysis: for each tunable parameter, run 150 games of
     * {@code defaults} vs {@code defaults +25%} and {@code defaults -25%}.
     *
     * <p><b>Why head-to-head?</b> Round-robin of all 25 variants pollutes the signal —
     * variants compete against each other, not just the baseline. Head-to-head gives
     * clean isolation: each parameter is tested independently.
     *
     * <p>Results print one line at a time as each 150-game batch finishes (parallelised internally).
     * With 150 games the 95% CI is ±8 pp. Treat anything below ±10 pp as noise.
     * A ▲/▼ marker means the variant beats/loses to defaults by more than 10 pp.
     *
     * <p>Run this before evolutionary search to know which params are worth evolving.
     */
    @Test
    @Disabled("Run manually for parameter sensitivity analysis (~4 min, incremental output)")
    void ablationStudy() {
        final int GAMES    = 150;   // 95% CI ≈ ±8 pp — anything below ±10 pp is noise
        final long SEED    = 42424242L;
        final double DELTA = 0.25;  // ±25% perturbation

        StrongBotConfig base = StrongBotConfig.defaults();
        System.out.printf("Ablation: defaults vs ±%.0f%% variants — %d games/pair%n%n", DELTA * 100, GAMES);
        header("Parameter", "Variant", "BaseWin%", "VarWin%", "Delta%", "Signal");
        System.out.println("-".repeat(90));

        // Each param's results print immediately when its batch finishes.
        // roundRobin() uses parallelStream() internally, so each 150-game batch is fast.
        List<AblationResult> results = new ArrayList<>();

        runAblation(results, "buyThreshold+25%",    base, base.toBuilder().buyThreshold(clamp(base.buyThreshold() * (1+DELTA), 4.0, 9.0)).build(),    GAMES, SEED);
        runAblation(results, "buyThreshold-25%",    base, base.toBuilder().buyThreshold(clamp(base.buyThreshold() * (1-DELTA), 4.0, 9.0)).build(),    GAMES, SEED + 1);
        runAblation(results, "minCashReserve+25%",  base, base.toBuilder().minCashReserve(clampI((int)(base.minCashReserve() * (1+DELTA)), 80, 450)).build(),  GAMES, SEED + 2);
        runAblation(results, "minCashReserve-25%",  base, base.toBuilder().minCashReserve(clampI((int)(base.minCashReserve() * (1-DELTA)), 80, 450)).build(),  GAMES, SEED + 3);
        runAblation(results, "dangerCash+25%",      base, base.toBuilder().dangerCashReserve(clampI((int)(base.dangerCashReserve() * (1+DELTA)), 200, 700)).build(), GAMES, SEED + 4);
        runAblation(results, "dangerCash-25%",      base, base.toBuilder().dangerCashReserve(clampI((int)(base.dangerCashReserve() * (1-DELTA)), 200, 700)).build(), GAMES, SEED + 5);
        runAblation(results, "buildAggression+25%", base, base.toBuilder().houseBuildAggression(clamp(base.houseBuildAggression() * (1+DELTA), 0.4, 2.0)).build(), GAMES, SEED + 6);
        runAblation(results, "buildAggression-25%", base, base.toBuilder().houseBuildAggression(clamp(base.houseBuildAggression() * (1-DELTA), 0.4, 2.0)).build(), GAMES, SEED + 7);
        runAblation(results, "hotelAversion+25%",   base, base.toBuilder().hotelAversion(clamp(base.hotelAversion() * (1+DELTA), 1.5, 10.0)).build(),   GAMES, SEED + 8);
        runAblation(results, "hotelAversion-25%",   base, base.toBuilder().hotelAversion(clamp(base.hotelAversion() * (1-DELTA), 1.5, 10.0)).build(),   GAMES, SEED + 9);
        runAblation(results, "liquidityPenalty+25%",base, base.toBuilder().liquidityPenaltyWeight(clamp(base.liquidityPenaltyWeight() * (1+DELTA), 1.0, 6.0)).build(), GAMES, SEED + 10);
        runAblation(results, "liquidityPenalty-25%",base, base.toBuilder().liquidityPenaltyWeight(clamp(base.liquidityPenaltyWeight() * (1-DELTA), 1.0, 6.0)).build(), GAMES, SEED + 11);
        runAblation(results, "blockWeight+25%",     base, base.toBuilder().opponentBlockWeight(clamp(base.opponentBlockWeight() * (1+DELTA), 2.0, 10.0)).build(),    GAMES, SEED + 12);
        runAblation(results, "blockWeight-25%",     base, base.toBuilder().opponentBlockWeight(clamp(base.opponentBlockWeight() * (1-DELTA), 2.0, 10.0)).build(),    GAMES, SEED + 13);
        runAblation(results, "railroadWeight+25%",  base, base.toBuilder().railroadWeight(clamp(base.railroadWeight() * (1+DELTA), 1.5, 5.5)).build(),  GAMES, SEED + 14);
        runAblation(results, "railroadWeight-25%",  base, base.toBuilder().railroadWeight(clamp(base.railroadWeight() * (1-DELTA), 1.5, 5.5)).build(),  GAMES, SEED + 15);
        runAblation(results, "auctionAggr+25%",     base, base.toBuilder().auctionAggression(clamp(base.auctionAggression() * (1+DELTA), 0.5, 1.5)).build(),     GAMES, SEED + 16);
        runAblation(results, "auctionAggr-25%",     base, base.toBuilder().auctionAggression(clamp(base.auctionAggression() * (1-DELTA), 0.5, 1.5)).build(),     GAMES, SEED + 17);
        runAblation(results, "tradeTolerance+25%",  base, base.toBuilder().tradeFairnessTolerance(clampI((int)(base.tradeFairnessTolerance() * (1+DELTA)), -30, 80)).build(), GAMES, SEED + 18);
        runAblation(results, "tradeTolerance-25%",  base, base.toBuilder().tradeFairnessTolerance(clampI((int)(base.tradeFairnessTolerance() * (1-DELTA)), -30, 80)).build(), GAMES, SEED + 19);
        runAblation(results, "buildReserve+25%",    base, base.toBuilder().buildReservePerOpponentMonopoly(clampI((int)(base.buildReservePerOpponentMonopoly() * (1+DELTA)), 20, 180)).build(), GAMES, SEED + 20);
        runAblation(results, "buildReserve-25%",    base, base.toBuilder().buildReservePerOpponentMonopoly(clampI((int)(base.buildReservePerOpponentMonopoly() * (1-DELTA)), 20, 180)).build(), GAMES, SEED + 21);
        runAblation(results, "progressWeight+25%",  base, base.toBuilder().progressWeight(clamp(base.progressWeight() * (1+DELTA), 1.0, 5.0)).build(),  GAMES, SEED + 22);
        runAblation(results, "progressWeight-25%",  base, base.toBuilder().progressWeight(clamp(base.progressWeight() * (1-DELTA), 1.0, 5.0)).build(),  GAMES, SEED + 23);
        runAblation(results, "developmentBias+25%", base, base.toBuilder().developmentBias(clamp(base.developmentBias() * (1+DELTA), 1.0, 5.0)).build(), GAMES, SEED + 24);
        runAblation(results, "developmentBias-25%", base, base.toBuilder().developmentBias(clamp(base.developmentBias() * (1-DELTA), 1.0, 5.0)).build(), GAMES, SEED + 25);

        // Summary: sorted by absolute delta, biggest impact first
        System.out.println("\n" + "=".repeat(90));
        System.out.println("SUMMARY — sorted by absolute impact on defaults:");
        System.out.println("=".repeat(90));
        results.stream()
                .sorted(Comparator.comparingDouble((AblationResult r) -> Math.abs(r.delta())).reversed())
                .forEach(r -> System.out.printf("  %-28s  delta=%+6.1f pp  %s%n",
                        r.name(), r.delta() * 100, signal(r.delta())));
    }

    // =========================================================================
    // Evolutionary search — 2-player optimal config (~8 min, per-gen output)
    // =========================================================================

    /**
     * Evolutionary search for the best 2-player bot config.
     *
     * <p>Each generation's standings print immediately. You can kill the JVM early and
     * copy-paste the diff of the most recent best config into {@link StrongBotConfig#defaults()}.
     *
     * <p>Parameters: pop=16 configs, 20 generations, 40 games/pair.
     * 40 games/pair × 120 pairs/gen = 4800 games/gen, fully parallelised.
     * Each generation takes ~25s on an 8-core machine.
     */
    @Test
    @Disabled("Long (~8 min, 20 gens) — run manually; kill early to use partial results")
    void evolveSmallGame() {
        int pop = 16, gens = 20, games = 40;
        long seed = 77777L;

        System.out.printf("Evolving 2-player config: pop=%d gens=%d games/pair=%d%n", pop, gens, games);
        System.out.println("Each generation prints immediately — kill early to use partial results.\n");

        BotTournament.Entry best = BotTournament.evolve(pop, gens, games, 2,
                List.of(new BotTournament.Entry("defaults",   StrongBotConfig.defaults()),
                        new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                        new BotTournament.Entry("cautious",   StrongBotConfig.cautious())),
                seed, true);

        System.out.println("\n" + "=".repeat(70));
        System.out.println("BEST 2-PLAYER CONFIG: " + best.name());
        printDiff(best.config(), StrongBotConfig.defaults(), "defaults");

        // Final verification against known presets (100 games/pair)
        System.out.println("\nVerification (100 games/pair):");
        printBracket("2-player verification", BotTournament.roundRobin(List.of(
                new BotTournament.Entry("best",       best.config()),
                new BotTournament.Entry("defaults",   StrongBotConfig.defaults()),
                new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                new BotTournament.Entry("cautious",   StrongBotConfig.cautious())
        ), 100, seed + 999_999L));
    }

    // =========================================================================
    // Evolutionary search — 4-player optimal config (~10 min, per-gen output)
    // =========================================================================

    /**
     * Evolutionary search for the best 4-player bot config.
     *
     * <p>Uses {@code sampledTournament()} (now parallelised) instead of full round-robin.
     * Each generation's standings print immediately.
     *
     * <p>Parameters: pop=16, 20 generations, 300 sampled games/gen.
     * Each generation takes ~30s on an 8-core machine.
     */
    @Test
    @Disabled("Long (~10 min, 20 gens) — run manually; kill early to use partial results")
    void evolveLargeGame() {
        int pop = 16, gens = 20, gamesPerGen = 300;
        int playerCount = 4;   // change to 5 or 6 if you want to tune those player counts
        long seed = 88888L;

        System.out.printf("Evolving %d-player config: pop=%d gens=%d games/gen=%d%n",
                playerCount, pop, gens, gamesPerGen);
        System.out.println("Each generation prints immediately — kill early to use partial results.\n");

        BotTournament.Entry best = BotTournament.evolve(pop, gens, gamesPerGen, playerCount,
                List.of(new BotTournament.Entry("sixPlayer",  StrongBotConfig.sixPlayer()),
                        new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                        new BotTournament.Entry("defaults",   StrongBotConfig.defaults())),
                seed, true);

        System.out.println("\n" + "=".repeat(70));
        System.out.printf("BEST %d-PLAYER CONFIG: %s%n", playerCount, best.name());
        printDiff(best.config(), StrongBotConfig.sixPlayer(), "sixPlayer");

        // Final verification (300 sampled games)
        System.out.println("\nVerification (300 games):");
        printBracket(playerCount + "-player verification",
                BotTournament.sampledTournament(List.of(
                        new BotTournament.Entry("best",       best.config()),
                        new BotTournament.Entry("sixPlayer",  StrongBotConfig.sixPlayer()),
                        new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                        new BotTournament.Entry("defaults",   StrongBotConfig.defaults()),
                        new BotTournament.Entry("cautious",   StrongBotConfig.cautious())
                ), 300, playerCount, seed + 999_999L));
    }

    // =========================================================================
    // 6-player ablation — which params matter in large games? (~5 min)
    // =========================================================================

    /**
     * Same head-to-head ablation as {@link #ablationStudy()} but uses 4-player sampled
     * games against {@link StrongBotConfig#sixPlayer()} as baseline.
     *
     * <p>Results print as each parameter finishes.
     * 95% CI ≈ ±8 pp with 150 sampled games per test.
     */
    @Test
    @Disabled("Run manually for 4-6 player parameter analysis (~5 min, incremental output)")
    void sixPlayerAblation() {
        final int GAMES = 150;
        final int PLAYER_COUNT = 4;   // change to 5 or 6 to target larger game counts
        final long SEED = 66666L;
        final double DELTA = 0.25;

        StrongBotConfig base = StrongBotConfig.sixPlayer();
        System.out.printf("Ablation: sixPlayer vs ±%.0f%% — %d sampled %d-player games each%n%n",
                DELTA * 100, GAMES, PLAYER_COUNT);
        header("Parameter", "Variant", "BaseWin%", "VarWin%", "Delta%", "Signal");
        System.out.println("-".repeat(90));

        List<AblationResult> results = new ArrayList<>();

        // Run each parameter head-to-head via sampledTournament (parallelised internally)
        runAblation6p(results, PLAYER_COUNT, "buyThreshold+25%",    base, base.toBuilder().buyThreshold(clamp(base.buyThreshold() * (1+DELTA), 4.0, 9.0)).build(),    GAMES, SEED);
        runAblation6p(results, PLAYER_COUNT, "buyThreshold-25%",    base, base.toBuilder().buyThreshold(clamp(base.buyThreshold() * (1-DELTA), 4.0, 9.0)).build(),    GAMES, SEED + 1);
        runAblation6p(results, PLAYER_COUNT, "buildAggression+25%", base, base.toBuilder().houseBuildAggression(clamp(base.houseBuildAggression() * (1+DELTA), 0.4, 2.0)).build(), GAMES, SEED + 2);
        runAblation6p(results, PLAYER_COUNT, "buildAggression-25%", base, base.toBuilder().houseBuildAggression(clamp(base.houseBuildAggression() * (1-DELTA), 0.4, 2.0)).build(), GAMES, SEED + 3);
        runAblation6p(results, PLAYER_COUNT, "hotelAversion+25%",   base, base.toBuilder().hotelAversion(clamp(base.hotelAversion() * (1+DELTA), 1.5, 10.0)).build(), GAMES, SEED + 4);
        runAblation6p(results, PLAYER_COUNT, "hotelAversion-25%",   base, base.toBuilder().hotelAversion(clamp(base.hotelAversion() * (1-DELTA), 1.5, 10.0)).build(), GAMES, SEED + 5);
        runAblation6p(results, PLAYER_COUNT, "dangerCash+25%",      base, base.toBuilder().dangerCashReserve(clampI((int)(base.dangerCashReserve() * (1+DELTA)), 200, 700)).build(), GAMES, SEED + 6);
        runAblation6p(results, PLAYER_COUNT, "dangerCash-25%",      base, base.toBuilder().dangerCashReserve(clampI((int)(base.dangerCashReserve() * (1-DELTA)), 200, 700)).build(), GAMES, SEED + 7);
        runAblation6p(results, PLAYER_COUNT, "tradeTolerance+50%",  base, base.toBuilder().tradeFairnessTolerance(clampI((int)(base.tradeFairnessTolerance() * 1.5), -30, 80)).build(), GAMES, SEED + 8);
        runAblation6p(results, PLAYER_COUNT, "tradeTolerance-50%",  base, base.toBuilder().tradeFairnessTolerance(clampI((int)(base.tradeFairnessTolerance() / 2), -30, 80)).build(),   GAMES, SEED + 9);
        runAblation6p(results, PLAYER_COUNT, "blockWeight+25%",     base, base.toBuilder().opponentBlockWeight(clamp(base.opponentBlockWeight() * (1+DELTA), 2.0, 10.0)).build(),    GAMES, SEED + 10);
        runAblation6p(results, PLAYER_COUNT, "blockWeight-25%",     base, base.toBuilder().opponentBlockWeight(clamp(base.opponentBlockWeight() * (1-DELTA), 2.0, 10.0)).build(),    GAMES, SEED + 11);

        System.out.println("\n" + "=".repeat(90));
        System.out.println("SUMMARY — sorted by absolute impact:");
        System.out.println("=".repeat(90));
        results.stream()
                .sorted(Comparator.comparingDouble((AblationResult r) -> Math.abs(r.delta())).reversed())
                .forEach(r -> System.out.printf("  %-28s  delta=%+6.1f pp  %s%n",
                        r.name(), r.delta() * 100, signal(r.delta())));
    }

    // =========================================================================
    // Helpers — ablation runners
    // =========================================================================

    /** Head-to-head 2-player ablation: prints one result line immediately after games finish. */
    private static void runAblation(List<AblationResult> out, String name,
                                    StrongBotConfig base, StrongBotConfig variant,
                                    int games, long seed) {
        // roundRobin() uses parallelStream() internally — 150 games run in parallel
        List<BotTournament.Standing> s = BotTournament.roundRobin(List.of(
                new BotTournament.Entry("base", base),
                new BotTournament.Entry("var",  variant)
        ), games, seed);
        double baseWin = winRate(s, "base");
        double varWin  = winRate(s, "var");
        double delta   = varWin - baseWin;
        printAblationLine(name, "defaults", baseWin, varWin, delta);
        out.add(new AblationResult(name, delta));
        System.out.flush();
    }

    /** Head-to-head N-player ablation via sampledTournament (now parallelised). */
    private static void runAblation6p(List<AblationResult> out, int playerCount,
                                      String name, StrongBotConfig base, StrongBotConfig variant,
                                      int games, long seed) {
        // sampledTournament() is now parallelised — games run concurrently
        List<BotTournament.Standing> s = BotTournament.sampledTournament(List.of(
                new BotTournament.Entry("base", base),
                new BotTournament.Entry("var",  variant)
        ), games, playerCount, seed);
        double baseWin = winRate(s, "base");
        double varWin  = winRate(s, "var");
        double delta   = varWin - baseWin;
        printAblationLine(name, "sixPlayer", baseWin, varWin, delta);
        out.add(new AblationResult(name, delta));
        System.out.flush();
    }

    private record AblationResult(String name, double delta) {}

    // =========================================================================
    // Output helpers
    // =========================================================================

    private static void printBracket(String label, List<BotTournament.Standing> standings) {
        System.out.println("\n--- " + label + " ---");
        for (int i = 0; i < standings.size(); i++) {
            System.out.printf("  #%d  %s%n", i + 1, standings.get(i));
        }
        System.out.flush();
    }

    private static void header(String... cols) {
        System.out.printf("  %-28s  %-12s  %-9s  %-9s  %-9s  %s%n",
                (Object[]) cols);
    }

    private static void printAblationLine(String name, String baseName,
                                          double baseWin, double varWin, double delta) {
        System.out.printf("  %-28s  vs %-9s  base=%4.1f%%  var=%4.1f%%  Δ=%+5.1f pp  %s%n",
                name, baseName, baseWin * 100, varWin * 100, delta * 100, signal(delta));
    }

    /** ▲ if clearly positive, ▼ if clearly negative, ≈ if within noise. Threshold: ±10 pp. */
    private static String signal(double delta) {
        if (delta > 0.10) return "▲ IMPROVES";
        if (delta < -0.10) return "▼ HURTS";
        return "≈ noise";
    }

    // =========================================================================
    // Diff & Builder output — printed after evolutionary search
    // =========================================================================

    /**
     * Prints a full parameter diff between {@code best} and {@code reference}, then
     * prints a complete copy-pasteable Builder snippet so you can update the preset directly.
     *
     * <p>Parameters marked with ▲/▼ changed by more than 5 % from the reference.
     */
    private static void printDiff(StrongBotConfig best, StrongBotConfig ref, String refName) {
        record P(String name, double bestV, double refV) {}
        List<P> params = List.of(
                // --- Buying ---
                new P("buyThreshold",                    best.buyThreshold(),                    ref.buyThreshold()),
                new P("minCashReserve",                  best.minCashReserve(),                  ref.minCashReserve()),
                new P("dangerCashReserve",               best.dangerCashReserve(),               ref.dangerCashReserve()),
                new P("completionWeight",                best.completionWeight(),                ref.completionWeight()),
                new P("progressWeight",                  best.progressWeight(),                  ref.progressWeight()),
                new P("opponentBlockWeight",             best.opponentBlockWeight(),              ref.opponentBlockWeight()),
                new P("railroadWeight",                  best.railroadWeight(),                  ref.railroadWeight()),
                new P("utilityWeight",                   best.utilityWeight(),                   ref.utilityWeight()),
                new P("liquidityPenaltyWeight",          best.liquidityPenaltyWeight(),          ref.liquidityPenaltyWeight()),
                new P("opponentLeaderPressure",          best.opponentLeaderPressure(),          ref.opponentLeaderPressure()),
                new P("railroadCompletionWeight",        best.railroadCompletionWeight(),        ref.railroadCompletionWeight()),
                new P("utilityCompletionWeight",         best.utilityCompletionWeight(),         ref.utilityCompletionWeight()),
                // --- Building ---
                new P("houseBuildAggression",            best.houseBuildAggression(),            ref.houseBuildAggression()),
                new P("hotelAversion",                   best.hotelAversion(),                   ref.hotelAversion()),
                new P("developmentBias",                 best.developmentBias(),                 ref.developmentBias()),
                new P("buildRoundCap",                   best.buildRoundCap(),                   ref.buildRoundCap()),
                new P("buildReservePerOpponentMonopoly", best.buildReservePerOpponentMonopoly(), ref.buildReservePerOpponentMonopoly()),
                new P("postMonopolyCashBuffer",          best.postMonopolyCashBuffer(),          ref.postMonopolyCashBuffer()),
                // --- Mortgage ---
                new P("mortgageTolerance",               best.mortgageTolerance(),               ref.mortgageTolerance()),
                new P("unmortgageAggression",            best.unmortgageAggression(),            ref.unmortgageAggression()),
                new P("mortgageRecoveryPriority",        best.mortgageRecoveryPriority(),        ref.mortgageRecoveryPriority()),
                // --- Auction ---
                new P("auctionAggression",               best.auctionAggression(),               ref.auctionAggression()),
                new P("auctionSetCompletionBonus",       best.auctionSetCompletionBonus(),       ref.auctionSetCompletionBonus()),
                // --- Trading ---
                new P("tradeFairnessTolerance",          best.tradeFairnessTolerance(),          ref.tradeFairnessTolerance()),
                new P("tradeSetCompletionWeight",        best.tradeSetCompletionWeight(),        ref.tradeSetCompletionWeight()),
                new P("tradeLiquidityWeight",            best.tradeLiquidityWeight(),            ref.tradeLiquidityWeight())
                // Note: preferJailLateGame, jailExitThreshold, bankruptcyAversion,
                //       jailCardHoldBias are NOT YET WIRED into strategy code — see StrongBotConfig.
                //       colorGroupWeights are based on landing probability theory, not evolved.
        );

        System.out.printf("%n%-38s  %-12s  %-12s  %s%n", "Parameter", "Best", refName, "Change");
        System.out.println("-".repeat(80));
        for (P p : params) {
            double pct = p.refV() != 0 ? (p.bestV() - p.refV()) / Math.abs(p.refV()) * 100 : 0;
            String marker = Math.abs(pct) > 5 ? (pct > 0 ? "  ▲" : "  ▼") : "";
            System.out.printf("%-38s  %-12.3f  %-12.3f  %+.1f%%%s%n",
                    p.name(), p.bestV(), p.refV(), pct, marker);
        }

        // Copy-pasteable Builder output — paste directly into the preset method
        System.out.println("\n" + "=".repeat(70));
        System.out.println("COPY-PASTEABLE Builder snippet (paste into the preset method):");
        System.out.println("=".repeat(70));
        System.out.println("return new Builder()");

        for (P p : params) {
            double pct = p.refV() != 0 ? (p.bestV() - p.refV()) / Math.abs(p.refV()) * 100 : 0;
            String change = Math.abs(pct) > 5 ? String.format("  // was %.3f (%+.1f%%)", p.refV(), pct) : "";
            // Print int params without trailing .0
            String valStr = (p.bestV() == Math.floor(p.bestV()) && !Double.isInfinite(p.bestV()))
                    ? String.valueOf((long) p.bestV())
                    : String.format("%.4f", p.bestV());
            System.out.printf("        .%-38s(%s)%s%n", p.name(), valStr, change);
        }
        // Always-same fields
        System.out.printf("        .%-38s(%s)%n", "preferJailLateGame", best.preferJailLateGame());
        System.out.println("        .build();");
        System.out.flush();
    }

    // =========================================================================
    // Small helpers
    // =========================================================================

    private static double winRate(List<BotTournament.Standing> standings, String name) {
        return standings.stream().filter(s -> name.equals(s.name()))
                .findFirst().map(BotTournament.Standing::winRate).orElse(0.0);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampI(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
