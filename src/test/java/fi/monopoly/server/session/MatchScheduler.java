package fi.monopoly.server.session;

import fi.monopoly.server.bot.BotStrategy;

import java.util.*;
import java.util.stream.IntStream;

/**
 * Phase 2: parallel match runner with Wilson-CI aggregation and mirror matching.
 *
 * <p>Builds a set of {@link HeadlessGameRunner.MatchConfig}s from an {@link ExperimentSpec},
 * runs them in parallel across all available CPU cores, then aggregates per-strategy win counts
 * into a Wilson 95% confidence-interval report.</p>
 *
 * <h3>Mirror matching</h3>
 * <p>When {@link ExperimentSpec#mirror()} is true, every seed is played twice with the two
 * strategies' seats swapped. Common-random-number variance cancels out, dramatically reducing
 * the games needed to detect a real win-rate difference.</p>
 *
 * <h3>Typical usage</h3>
 * <pre>{@code
 * ExperimentSpec spec = new ExperimentSpec(
 *     List.of(
 *         new StrategyEntry("bot-a", new PureDomainStrategy(Map.of())),
 *         new StrategyEntry("bot-b", new PureDomainStrategy(StrongBotConfig.humanlike().asMap()))
 *     ),
 *     500,          // gamesPerPair (× 2 for mirror)
 *     true,         // mirror
 *     20_000,       // maxSteps
 *     42L           // seedBase
 * );
 * EvaluationReport report = MatchScheduler.run(spec);
 * System.out.println(report.summary());
 * }</pre>
 */
public final class MatchScheduler {

    /** One strategy in an experiment. */
    public record StrategyEntry(String name, BotStrategy strategy) {}

    /**
     * Full experiment specification.
     *
     * @param strategies  the competing strategies (currently 2-player only)
     * @param gamesPerPair number of seeds to play per pair (× 2 when mirror = true)
     * @param mirror      play each seed with seats swapped for variance reduction
     * @param maxSteps    per-game step cap passed to {@link HeadlessGameRunner.MatchConfig}
     * @param seedBase    first seed; game g uses seed = seedBase + g
     */
    public record ExperimentSpec(
            List<StrategyEntry> strategies,
            int gamesPerPair,
            boolean mirror,
            int maxSteps,
            long seedBase
    ) {}

    /**
     * Wilson 95% CI for a proportion.
     *
     * @param winShare point estimate (k / n)
     * @param lower    CI lower bound
     * @param upper    CI upper bound
     */
    public record WilsonInterval(double winShare, double lower, double upper) {
        static WilsonInterval of(int wins, int n) {
            if (n == 0) return new WilsonInterval(0, 0, 0);
            double z   = 1.96;
            double p   = (double) wins / n;
            double z2n = z * z / n;
            double center    = (p + z2n / 2) / (1 + z2n);
            double halfwidth = (z / (1 + z2n)) * Math.sqrt(p * (1 - p) / n + z2n / (4 * n));
            return new WilsonInterval(p, Math.max(0, center - halfwidth), Math.min(1, center + halfwidth));
        }
        @Override public String toString() {
            return String.format("%.1f%%  [%.1f%%, %.1f%%]", winShare*100, lower*100, upper*100);
        }
    }

    /** Per-strategy result in an {@link EvaluationReport}. */
    public record StrategyResult(
            String name,
            int wins,
            int games,
            int stalemates,
            WilsonInterval ci
    ) {
        @Override public String toString() {
            return String.format("%-24s  wins=%4d/%4d  stalemates=%3d  win-share=%s",
                    name, wins, games, stalemates, ci);
        }
    }

    /** Top-level result returned by {@link MatchScheduler#run}. */
    public record EvaluationReport(
            List<StrategyResult> results,
            int totalGames,
            int totalStalemates,
            int loopSuspectedCount,
            long seedBase,
            boolean mirror
    ) {
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("=".repeat(80)).append('\n');
            sb.append("Evaluation Report\n");
            sb.append(String.format("  games=%d  net-worth-decided=%d (%.1f%%)  undecided-draws=%d  mirror=%b%n",
                    totalGames, totalStalemates,
                    totalGames > 0 ? 100.0 * totalStalemates / totalGames : 0,
                    loopSuspectedCount, mirror));
            sb.append("WIN SHARE (Wilson 95% CI)\n");
            for (StrategyResult r : results) sb.append("  ").append(r).append('\n');

            if (results.size() == 2) {
                WilsonInterval a = results.get(0).ci(), b = results.get(1).ci();
                if (a.lower() > b.upper()) {
                    sb.append("→ ").append(results.get(0).name())
                      .append(" is better (CIs disjoint)\n");
                } else if (b.lower() > a.upper()) {
                    sb.append("→ ").append(results.get(1).name())
                      .append(" is better (CIs disjoint)\n");
                } else {
                    sb.append("→ No significant difference (CIs overlap)\n");
                }
            }
            sb.append("=".repeat(80));
            return sb.toString();
        }
    }

    private MatchScheduler() {}

    // -------------------------------------------------------------------------
    // Main API
    // -------------------------------------------------------------------------

    /**
     * Runs the experiment described by {@code spec} and returns an aggregated report.
     *
     * <p>Games are run in parallel using the common fork-join pool (one job per game).
     * Each game is fully independent — no shared mutable state between games.</p>
     */
    public static EvaluationReport run(ExperimentSpec spec) {
        if (spec.strategies().size() != 2) {
            throw new UnsupportedOperationException(
                    "MatchScheduler currently supports exactly 2 strategies (got "
                            + spec.strategies().size() + ")");
        }

        StrategyEntry entryA = spec.strategies().get(0);
        StrategyEntry entryB = spec.strategies().get(1);

        // Build all match configs upfront (deterministic, single-threaded)
        record MatchJob(HeadlessGameRunner.MatchConfig config, int strategyAtSeat0) {}

        List<MatchJob> jobs = new ArrayList<>();
        for (int g = 0; g < spec.gamesPerPair(); g++) {
            long seed = spec.seedBase() + g;
            // Normal: A at seat 0, B at seat 1
            jobs.add(new MatchJob(
                    new HeadlessGameRunner.MatchConfig(seed, seats(entryA, entryB), spec.maxSteps()), 0));
            // Mirror: B at seat 0, A at seat 1
            if (spec.mirror()) {
                jobs.add(new MatchJob(
                        new HeadlessGameRunner.MatchConfig(seed, seats(entryB, entryA), spec.maxSteps()), 1));
            }
        }

        // Run all games in parallel
        record JobResult(HeadlessGameRunner.GameResult result, int strategyAtSeat0) {}
        List<JobResult> outcomes = jobs.parallelStream()
                .map(j -> new JobResult(HeadlessGameRunner.play(j.config()), j.strategyAtSeat0()))
                .toList();

        // Aggregate
        int[] wins     = new int[2];
        int[] games    = new int[2];
        int totalStalemates = 0;
        int loopCount = 0;

        for (JobResult r : outcomes) {
            games[0]++; games[1]++;
            HeadlessGameRunner.GameResult gr = r.result();
            if (gr.loopSuspected()) loopCount++;
            if (gr.outcome() == HeadlessGameRunner.Outcome.STALEMATE) totalStalemates++;
            // Honour the winner whenever the game is decidable. A game that reaches the step cap
            // without a bankruptcy is decided by net worth (standard timed-Monopoly scoring), so its
            // winnerSeat is valid — only an exact net-worth tie / suspected loop yields -1, which is
            // the only genuine draw. This is what makes 3-4 player games (which rarely bankrupt out
            // within the step cap) measurable instead of ~80% discarded.
            int winnerSeat = gr.winnerSeat();
            if (winnerSeat == 0) {
                wins[r.strategyAtSeat0()]++;
            } else if (winnerSeat == 1) {
                wins[1 - r.strategyAtSeat0()]++;
            }
        }

        List<StrategyResult> results = List.of(
                new StrategyResult(entryA.name(), wins[0], games[0], 0, WilsonInterval.of(wins[0], games[0])),
                new StrategyResult(entryB.name(), wins[1], games[1], 0, WilsonInterval.of(wins[1], games[1]))
        );

        return new EvaluationReport(results, outcomes.size(), totalStalemates, loopCount,
                spec.seedBase(), spec.mirror());
    }

    /**
     * 4-player head-to-head A/B: two seats run strategy {@code a}, two run {@code b}, rotated over
     * all six distinct {A,A,B,B} seat arrangements to cancel positional advantage. Each game is
     * decided by bankruptcy or — at the step cap — net worth, so games that don't bankrupt out
     * (the 4-player norm) still count. This is the regime where denial / opponent-modelling levers
     * actually fire, so it is the harness to validate them.
     *
     * @param seedsPerArrangement seeds played per arrangement (× 6 arrangements = total games)
     */
    public static EvaluationReport runFourPlayerAB(StrategyEntry a, StrategyEntry b,
                                                   int seedsPerArrangement, int maxSteps, long seedBase) {
        boolean[][] arrangements = {
                {true, true, false, false}, {true, false, true, false}, {true, false, false, true},
                {false, true, true, false}, {false, true, false, true}, {false, false, true, true}
        }; // true ⇒ that seat runs strategy A

        record Job(HeadlessGameRunner.MatchConfig cfg, boolean[] layout) {}
        List<Job> jobs = new ArrayList<>();
        for (boolean[] layout : arrangements) {
            for (int s = 0; s < seedsPerArrangement; s++) {
                long seed = seedBase + s;
                List<HeadlessGameRunner.SeatAssignment> seats = new ArrayList<>();
                for (boolean isA : layout) {
                    seats.add(new HeadlessGameRunner.SeatAssignment(isA ? a.strategy() : b.strategy()));
                }
                jobs.add(new Job(new HeadlessGameRunner.MatchConfig(seed, seats, maxSteps), layout));
            }
        }

        record JobResult(HeadlessGameRunner.GameResult result, boolean[] layout) {}
        List<JobResult> outcomes = jobs.parallelStream()
                .map(j -> new JobResult(HeadlessGameRunner.play(j.cfg()), j.layout()))
                .toList();

        int aWins = 0, bWins = 0, draws = 0, tiebreak = 0;
        for (JobResult r : outcomes) {
            if (r.result().outcome() == HeadlessGameRunner.Outcome.STALEMATE) tiebreak++;
            int w = r.result().winnerSeat();
            if (w < 0) { draws++; continue; }
            if (r.layout()[w]) aWins++; else bWins++;
        }
        int decided = aWins + bWins;
        List<StrategyResult> results = List.of(
                new StrategyResult(a.name(), aWins, decided, 0, WilsonInterval.of(aWins, decided)),
                new StrategyResult(b.name(), bWins, decided, 0, WilsonInterval.of(bWins, decided))
        );
        return new EvaluationReport(results, outcomes.size(), tiebreak, draws, seedBase, false);
    }

    // -------------------------------------------------------------------------
    // Convenience factory
    // -------------------------------------------------------------------------

    /** Creates an {@link ExperimentSpec} for a head-to-head mirror duel with defaults. */
    public static ExperimentSpec mirrorDuel(StrategyEntry a, StrategyEntry b,
                                             int gamesPerPair, long seedBase) {
        return new ExperimentSpec(List.of(a, b), gamesPerPair, true, 20_000, seedBase);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<HeadlessGameRunner.SeatAssignment> seats(StrategyEntry a, StrategyEntry b) {
        return List.of(new HeadlessGameRunner.SeatAssignment(a.strategy()),
                       new HeadlessGameRunner.SeatAssignment(b.strategy()));
    }
}
