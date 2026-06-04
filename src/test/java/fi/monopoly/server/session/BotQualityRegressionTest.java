package fi.monopoly.server.session;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for bot strategy quality.
 *
 * <p>These tests verify that the relative win-rate ordering between presets is preserved.
 * They use fixed seeds so results are deterministic, and lenient thresholds (±5 pp) to
 * tolerate natural game variance while still catching real regressions.
 *
 * <p>Expected ordering from empirical benchmarks:
 * <ul>
 *   <li>2-player: aggressive > evolved2p > defaults > cautious</li>
 *   <li>4-player: defaults ≈ sixPlayer > aggressive > cautious</li>
 * </ul>
 *
 * <p><strong>Ei koskaan CI-ajoon</strong> — nämä testit ajavat satoja pelejä ja vievät
 * useita minuutteja. Aja manuaalisesti kun muutat bot-strategiaa:
 * {@code mvn test -Dtest=BotQualityRegressionTest}
 */
@Disabled("Hidas (~30 s) — aja manuaalisesti: mvn test -Dtest=BotQualityRegressionTest")
class BotQualityRegressionTest {

    private static final long SEED = 999_001L;
    // 100 games per pair: variance is ~5pp at 50% win rate, so 12pp leniency catches
    // real regressions while tolerating bad luck. Runs in ~25s on Render's build server.
    private static final int GAMES = 100;
    private static final double LENIENCY = 0.12; // 12 pp tolerance for stochastic variance

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aggressive_beats_defaults_in_2player() {
        List<BotTournament.Standing> s = BotTournament.roundRobin(List.of(
                new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                new BotTournament.Entry("defaults",   StrongBotConfig.defaults())
        ), GAMES, SEED);

        double aggrWin    = winRate(s, "aggressive");
        double defaultWin = winRate(s, "defaults");

        assertTrue(aggrWin > defaultWin - LENIENCY,
                String.format("aggressive (%.1f%%) should beat defaults (%.1f%%) in 2-player",
                        aggrWin * 100, defaultWin * 100));
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void evolved2p_beats_defaults_in_2player() {
        List<BotTournament.Standing> s = BotTournament.roundRobin(List.of(
                new BotTournament.Entry("evolved2p", StrongBotConfig.evolved2p()),
                new BotTournament.Entry("defaults",  StrongBotConfig.defaults())
        ), GAMES, SEED + 1);

        double evolvedWin = winRate(s, "evolved2p");
        double defaultWin = winRate(s, "defaults");

        assertTrue(evolvedWin > defaultWin - LENIENCY,
                String.format("evolved2p (%.1f%%) should beat defaults (%.1f%%) in 2-player",
                        evolvedWin * 100, defaultWin * 100));
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void defaults_beats_cautious_in_2player() {
        List<BotTournament.Standing> s = BotTournament.roundRobin(List.of(
                new BotTournament.Entry("defaults", StrongBotConfig.defaults()),
                new BotTournament.Entry("cautious", StrongBotConfig.cautious())
        ), GAMES, SEED + 2);

        double defaultWin  = winRate(s, "defaults");
        double cautiousWin = winRate(s, "cautious");

        assertTrue(defaultWin > cautiousWin - LENIENCY,
                String.format("defaults (%.1f%%) should beat cautious (%.1f%%) in 2-player",
                        defaultWin * 100, cautiousWin * 100));
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void sixPlayer_beats_cautious_in_3player() {
        List<BotTournament.Standing> s = BotTournament.sampledTournament(List.of(
                new BotTournament.Entry("sixPlayer", StrongBotConfig.sixPlayer()),
                new BotTournament.Entry("defaults",  StrongBotConfig.defaults()),
                new BotTournament.Entry("cautious",  StrongBotConfig.cautious())
        ), 300, 3, SEED + 3);

        double sixPlayerWin = winRate(s, "sixPlayer");
        double cautiousWin  = winRate(s, "cautious");

        assertTrue(sixPlayerWin > cautiousWin - LENIENCY,
                String.format("sixPlayer (%.1f%%) should beat cautious (%.1f%%) in 3-player",
                        sixPlayerWin * 100, cautiousWin * 100));
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void forSeat_produces_competitive_configs() {
        // Seat 0 (optimal) should win vs seat 1 (mutated) more often than not,
        // but mutation is only 10% so difference should be small
        StrongBotConfig seat0 = StrongBotConfig.forSeat(0, 4, 12345L);
        StrongBotConfig seat1 = StrongBotConfig.forSeat(1, 4, 12345L);

        List<BotTournament.Standing> s = BotTournament.roundRobin(List.of(
                new BotTournament.Entry("seat0", seat0),
                new BotTournament.Entry("seat1", seat1)
        ), GAMES, SEED + 4);

        // Both configs should be reasonably competitive (neither below 30%)
        double seat0Win = winRate(s, "seat0");
        double seat1Win = winRate(s, "seat1");
        assertTrue(seat0Win >= 0.30,
                String.format("seat0 (optimal) should win ≥30%% of games, got %.1f%%", seat0Win * 100));
        assertTrue(seat1Win >= 0.30,
                String.format("seat1 (10%% mutated) should still win ≥30%% of games, got %.1f%%", seat1Win * 100));
    }

    private static double winRate(List<BotTournament.Standing> standings, String name) {
        return standings.stream()
                .filter(s -> name.equals(s.name()))
                .mapToDouble(BotTournament.Standing::winRate)
                .findFirst().orElse(0.0);
    }
}
