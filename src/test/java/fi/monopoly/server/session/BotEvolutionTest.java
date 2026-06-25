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
 * <p>All tests are {@code @Disabled} so they never run in CI.
 * Run them one at a time from the terminal (see commands below).
 *
 * =========================================================================
 * STEP-BY-STEP GUIDE: how to improve the 2–3 player bot without AI help
 * =========================================================================
 *
 * WHAT YOU'RE TUNING
 *   The 2-player bot uses {@link StrongBotConfig#aggressive()} (see {@code forPlayerCount(2)}).
 *   The 3-player bot uses {@link StrongBotConfig#sixPlayer()} (see {@code forPlayerCount(3)}).
 *   All tunable parameters and what they do are documented in {@link StrongBotConfig}.
 *
 * ABOUT BOT-VS-BOT TRAINING AND HUMAN OPPONENTS
 *   Evolution runs bot-vs-bot games, which risks the bot learning to exploit patterns
 *   that other bots share but humans don't (e.g. systematic blocking, precise cash
 *   management). To guard against this:
 *   - {@link StrongBotConfig#humanlike()} is included in all evolution seed populations
 *     so the evolved bot must beat human-like play too.
 *   - The final verification step always includes a humanlike bracket.
 *   - If the evolved bot beats humanlike bots by a large margin, it's robust.
 *     If it barely beats them, the strategy code itself needs improvement (see LIMITATIONS).
 *
 * ABOUT TOO MANY PARAMETERS
 *   There are 25+ parameters but only ~6 typically move the needle significantly.
 *   Workflow: run ablation first to identify the high-signal parameters, then use
 *   {@link StrongBotConfig#focusedMutate} (tuning only buyThreshold, minCashReserve,
 *   houseBuildAggression, hotelAversion, liquidityPenaltyWeight, opponentBlockWeight)
 *   for faster convergence. The focused evolution tests below use this approach.
 *
 * -------------------------------------------------------------------------
 * STEP 1 — find out which parameters matter most  (~4 min)
 * -------------------------------------------------------------------------
 *   mvn test -Dtest=BotEvolutionTest#ablationStudy -pl . -Dsurefire.failIfNoSpecifiedTests=false
 *
 *   Output: one line per parameter, printed as it finishes. Example:
 *     buildAggression+25%           vs defaults   base=50.0%  var=61.3%  Δ=+11.3 pp  ▲ IMPROVES
 *     hotelAversion-25%             vs defaults   base=50.0%  var=38.7%  Δ=-11.3 pp  ▼ HURTS
 *     railroadWeight+25%            vs defaults   base=50.0%  var=52.0%  Δ= +2.0 pp  ≈ noise
 *
 *   Reading the output:
 *     ▲ IMPROVES  = the +25% variant beats defaults by >10 pp — this direction is worth pursuing
 *     ▼ HURTS     = the +25% variant loses by >10 pp — go the other direction or leave it
 *     ≈ noise     = difference is within the 95% confidence interval — this param doesn't matter much
 *
 *   Confidence: with 150 games the margin of error is ±8 pp.
 *   Ignore any result with |Δ| < 10 pp — it's statistical noise.
 *
 * -------------------------------------------------------------------------
 * STEP 2 — let evolution find a better config automatically  (~8–10 min)
 * -------------------------------------------------------------------------
 *   2-player (improves aggressive preset, trains against humanlike too):
 *   mvn test -Dtest=BotEvolutionTest#evolveSmallGame -pl . -Dsurefire.failIfNoSpecifiedTests=false
 *
 *   3-player (improves sixPlayer preset, trains against humanlike too):
 *   mvn test -Dtest=BotEvolutionTest#evolve3Player -pl . -Dsurefire.failIfNoSpecifiedTests=false
 *
 *   You can kill the process early (Ctrl+C) — it prints the best config found so far
 *   at the end of each generation. Each generation takes ~25–30 s on a modern laptop.
 *
 *   Output per generation looks like:
 *     === Generation 3 (playerCount=2) ===
 *       child-2-4     W=31  L=9  D=0  (40 games)  win%=77.5  avgSteps=1840
 *       defaults      W=28  L=12 D=0  (40 games)  win%=70.0  avgSteps=1901
 *       ...
 *
 *   The top entry in each generation is the current best. At the end of all
 *   generations (or when you kill it), you get a full parameter diff + Builder snippet.
 *
 * -------------------------------------------------------------------------
 * STEP 3 — copy-paste the result into the preset  (~2 min)
 * -------------------------------------------------------------------------
 *   After evolution finishes (or after you kill it), the output contains:
 *
 *     COPY-PASTEABLE Builder snippet (paste into the preset method):
 *     return new Builder()
 *         .buyThreshold(3.8120)          // was 4.0000 (-4.7%)
 *         .houseBuildAggression(1.7431)  // was 1.5000 (+16.2%) ▲
 *         ...
 *         .build();
 *
 *   Open {@link StrongBotConfig} and paste this into the matching preset method:
 *   - 2-player result → replace the body of {@code aggressive()}
 *   - 3-player result → replace the body of {@code sixPlayer()}
 *
 *   The diff table above the snippet shows which params changed and by how much.
 *   Params with ▲/▼ changed more than 5 % — those are the meaningful changes.
 *
 * -------------------------------------------------------------------------
 * STEP 4 — verify the new preset actually wins — including vs humanlike  (~90 s)
 * -------------------------------------------------------------------------
 *   mvn test -Dtest=BotEvolutionTest#quickBenchmark -pl . -Dsurefire.failIfNoSpecifiedTests=false
 *
 *   This compares all presets across 2/3/4/6-player brackets, including a humanlike bracket.
 *   The new preset should rank higher than the old one in its target bracket.
 *   Check the humanlike brackets: if your preset wins ≥60% vs humanlike, it's robust.
 *   If it doesn't, the evolution overfit to bot-specific patterns — discard and re-run with
 *   a different seed (change the {@code seed} variable in the test method).
 *
 * =========================================================================
 * WHAT THE NUMBERS MEAN
 * =========================================================================
 *
 *   win%  = percentage of games won (50% = random, >60% = meaningfully better)
 *   Δ pp  = percentage-point difference from baseline (base is always ~50% in head-to-head)
 *   95% CI with 40 games/pair: ±15 pp  (coarse — trust only big deltas)
 *   95% CI with 100 games/pair: ±10 pp  (ablation uses 150 → ±8 pp)
 *
 *   A preset that wins 58% in a 100-game bracket is reliably better.
 *   A preset that wins 53% might just be luck — run more games if unsure.
 *
 * =========================================================================
 * IMPORTANT LIMITATIONS
 * =========================================================================
 *
 *   Config tuning only goes so far. The bot strategy code itself (StrongBotStrategy,
 *   BotTournament dispatch) has structural limitations that no config value can fix:
 *
 *   - Trade logic is simple: offer one property + cash. No property-for-property swaps.
 *   - Auction logic in BotTournament does not model opponents' cash levels (only PureDomainBotDriver does).
 *   - Debt logic always sells the cheapest asset first.
 *
 *   Fixing these requires code changes in StrongBotStrategy / BotTournament — that's
 *   where to go after config tuning hits a ceiling.
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
                new BotTournament.Entry("sixPlayer",  StrongBotConfig.sixPlayer()),
                new BotTournament.Entry("humanlike",  StrongBotConfig.humanlike())
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

        // Sanity check: all strong presets should beat humanlike clearly (≥60%).
        // If a preset barely beats humanlike, it's overfit to bot-specific play patterns.
        System.out.println("\n[Human-robustness check: each preset 1-on-1 vs humanlike, 200 games]");
        List<BotTournament.Entry> humanCheck = List.of(
                new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                new BotTournament.Entry("humanlike",  StrongBotConfig.humanlike())
        );
        printBracket("2p aggressive vs humanlike", BotTournament.roundRobin(humanCheck, 200, 99999L));
        List<BotTournament.Entry> humanCheck3 = List.of(
                new BotTournament.Entry("sixPlayer",  StrongBotConfig.sixPlayer()),
                new BotTournament.Entry("humanlike",  StrongBotConfig.humanlike())
        );
        printBracket("3p sixPlayer vs humanlike", BotTournament.sampledTournament(humanCheck3, 200, 3, 88888L));
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
    @Disabled("Long (~37 min, 20 gens) — run manually; kill early to use partial results")
    void evolveSmallGame() {
        int pop = 16, gens = 20, games = 40;
        long seed = 77777L;

        System.out.printf("Evolving 2-player config: pop=%d gens=%d games/pair=%d%n", pop, gens, games);
        System.out.println("humanlike is included in the seed population — evolved config must beat it too.");
        System.out.println("Each generation prints immediately — kill early to use partial results.\n");

        // humanlike is included so the evolved config can't specialise only against other strong bots
        BotTournament.Entry best = BotTournament.evolve(pop, gens, games, 2,
                List.of(new BotTournament.Entry("defaults",   StrongBotConfig.defaults()),
                        new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                        new BotTournament.Entry("cautious",   StrongBotConfig.cautious()),
                        new BotTournament.Entry("humanlike",  StrongBotConfig.humanlike())),
                seed, true);

        System.out.println("\n" + "=".repeat(70));
        System.out.println("BEST 2-PLAYER CONFIG: " + best.name());
        printDiff(best.config(), StrongBotConfig.defaults(), "defaults");

        // Verification: strong presets + humanlike robustness check
        System.out.println("\nVerification (100 games/pair):");
        printBracket("2-player verification", BotTournament.roundRobin(List.of(
                new BotTournament.Entry("best",       best.config()),
                new BotTournament.Entry("defaults",   StrongBotConfig.defaults()),
                new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                new BotTournament.Entry("cautious",   StrongBotConfig.cautious()),
                new BotTournament.Entry("humanlike",  StrongBotConfig.humanlike())
        ), 100, seed + 999_999L));
        System.out.println("  → best should win ≥60% vs humanlike; lower means overfitting to bot patterns.");
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
        int playerCount = 4;   // change to 3 for 3-player tuning
        long seed = 88888L;

        System.out.printf("Evolving %d-player config: pop=%d gens=%d games/gen=%d%n",
                playerCount, pop, gens, gamesPerGen);
        System.out.println("Each generation prints immediately — kill early to use partial results.\n");

        // humanlike included so evolved config must beat human-like play, not just strong bots
        BotTournament.Entry best = BotTournament.evolve(pop, gens, gamesPerGen, playerCount,
                List.of(new BotTournament.Entry("sixPlayer",  StrongBotConfig.sixPlayer()),
                        new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                        new BotTournament.Entry("defaults",   StrongBotConfig.defaults()),
                        new BotTournament.Entry("humanlike",  StrongBotConfig.humanlike())),
                seed, true);

        System.out.println("\n" + "=".repeat(70));
        System.out.printf("BEST %d-PLAYER CONFIG: %s%n", playerCount, best.name());
        printDiff(best.config(), StrongBotConfig.sixPlayer(), "sixPlayer");

        // Final verification (300 sampled games) + humanlike robustness check
        System.out.println("\nVerification (300 games):");
        printBracket(playerCount + "-player verification",
                BotTournament.sampledTournament(List.of(
                        new BotTournament.Entry("best",       best.config()),
                        new BotTournament.Entry("sixPlayer",  StrongBotConfig.sixPlayer()),
                        new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                        new BotTournament.Entry("defaults",   StrongBotConfig.defaults()),
                        new BotTournament.Entry("cautious",   StrongBotConfig.cautious()),
                        new BotTournament.Entry("humanlike",  StrongBotConfig.humanlike())
                ), 300, playerCount, seed + 999_999L));
        System.out.println("  → best should win ≥60% vs humanlike; lower means overfitting to bot patterns.");
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
