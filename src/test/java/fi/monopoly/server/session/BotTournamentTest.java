package fi.monopoly.server.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke-test for the bot tournament engine.
 * Runs a small tournament (3 configs × 10 games each pair) and verifies:
 *  - no exceptions / stalls
 *  - standings are complete
 *  - at least one config wins more than it draws
 *
 * For a full tournament or evolutionary search, run {@link BotTournament#main(String[])}
 * directly or use {@link BotTournament#evolve}.
 */
class BotTournamentTest {

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void smokeRoundRobinThreeConfigs() {
        List<BotTournament.Entry> configs = List.of(
                new BotTournament.Entry("defaults",   StrongBotConfig.defaults()),
                new BotTournament.Entry("aggressive", StrongBotConfig.aggressive()),
                new BotTournament.Entry("cautious",   StrongBotConfig.cautious())
        );

        List<BotTournament.Standing> standings = BotTournament.roundRobin(configs, 10, 1337L);

        assertEquals(3, standings.size(), "standings count should equal config count");
        for (BotTournament.Standing s : standings) {
            assertTrue(s.games() > 0, "each config should have played games: " + s.name());
            assertEquals(s.games(), s.wins() + s.losses() + s.draws(),
                    "wins+losses+draws should equal games: " + s.name());
        }
        // Total games played across all configs = 2 × (pairs × gamesPerPair)
        int totalGames = standings.stream().mapToInt(BotTournament.Standing::games).sum();
        assertEquals(60, totalGames, "3 configs × 10 games/pair × 2 (each game counts for 2 configs)");

        // Each game should produce a winner (net-worth tiebreaker, no true draws)
        int totalWins = standings.stream().mapToInt(BotTournament.Standing::wins).sum();
        assertEquals(30, totalWins, "every game should have exactly one winner");

        BotTournament.printStandings(standings);
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void singleGameRunsToCompletion() {
        List<StrongBotConfig> cfgs = List.of(StrongBotConfig.defaults(), StrongBotConfig.aggressive());
        int winner = BotTournament.runGame(cfgs, 42L);
        // winner is 0 or 1 (valid seat) or -1 (genuine stall — shouldn't happen normally)
        assertTrue(winner >= -1 && winner <= 1, "winner should be a valid seat index or -1: " + winner);
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void mutateProducesValidConfig() {
        java.util.Random rng = new java.util.Random(99L);
        StrongBotConfig original = StrongBotConfig.defaults();

        // Apply 12 mutations (covers all tunable params at least once on average)
        boolean anyChanged = false;
        for (int i = 0; i < 12; i++) {
            StrongBotConfig mutated = original.mutate(new java.util.Random(i * 17L), 0.25);
            // Frozen params must never change
            assertTrue(mutated.buyToBlockOpponent());
            assertTrue(mutated.prioritizeThreeHouses());
            assertTrue(mutated.preferJailLateGame());
            assertEquals(5, mutated.buildRoundCap());
            // Check if this mutation changed anything
            if (mutated.buyThreshold() != original.buyThreshold() ||
                    mutated.minCashReserve() != original.minCashReserve() ||
                    mutated.dangerCashReserve() != original.dangerCashReserve() ||
                    mutated.houseBuildAggression() != original.houseBuildAggression() ||
                    mutated.hotelAversion() != original.hotelAversion() ||
                    mutated.liquidityPenaltyWeight() != original.liquidityPenaltyWeight() ||
                    mutated.opponentBlockWeight() != original.opponentBlockWeight() ||
                    mutated.railroadWeight() != original.railroadWeight() ||
                    mutated.utilityWeight() != original.utilityWeight() ||
                    mutated.auctionAggression() != original.auctionAggression() ||
                    mutated.tradeFairnessTolerance() != original.tradeFairnessTolerance() ||
                    mutated.buildReservePerOpponentMonopoly() != original.buildReservePerOpponentMonopoly()) {
                anyChanged = true;
            }
        }
        assertTrue(anyChanged, "at least one of 12 mutations should change a tunable parameter");
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void crossoverPreservesFrozenParams() {
        java.util.Random rng = new java.util.Random(7L);
        StrongBotConfig child = StrongBotConfig.defaults().crossover(StrongBotConfig.aggressive(), rng);
        assertTrue(child.buyToBlockOpponent());
        assertTrue(child.prioritizeThreeHouses());
        assertTrue(child.preferJailLateGame());
        assertEquals(5, child.buildRoundCap());
    }
}
