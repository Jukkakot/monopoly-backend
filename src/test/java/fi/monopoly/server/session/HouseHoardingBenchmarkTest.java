package fi.monopoly.server.session;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * 4-player A/B benchmark for the housing-shortage weapon (houseHoardingWeight).
 *
 * <p>When houses are scarce (&lt;8 left in the bank) and an opponent has an undeveloped monopoly,
 * the weapon (1) rushes to lock up remaining houses (+4*weight to build score) and (2) penalises
 * hotel conversions that would return 4 houses to the bank (-8*weight additional aversion).
 * In 2-player the weapon never fires (no house scarcity with one monopoly each). 4-player is the
 * regime where multiple monopolies compete and the 32-house bank runs out.
 *
 * <p>Run manually from the IDE — takes ~3 minutes for 600 games on a modern laptop.</p>
 */
@Disabled("manual benchmark — run from IDE")
final class HouseHoardingBenchmarkTest {

    @Test
    void houseHoardingWeaponFourPlayer() {
        StrongBotConfig weaponOn  = StrongBotConfig.defaults();                               // houseHoardingWeight = 1.0
        StrongBotConfig weaponOff = StrongBotConfig.defaults().toBuilder().houseHoardingWeight(0.0).build();

        var report = MatchScheduler.runFourPlayerAB(
                new MatchScheduler.StrategyEntry("hoarding-on",  new PureDomainStrategy(weaponOn)),
                new MatchScheduler.StrategyEntry("hoarding-off", new PureDomainStrategy(weaponOff)),
                17, 20_000, 77_000L);  // 17 seeds × 6 arrangements = 102 games

        System.out.println(report.summary());
        System.out.printf("Decided: %d/%d (%.1f%%)%n",
                report.totalGames() - report.loopSuspectedCount(),
                report.totalGames(),
                100.0 * (report.totalGames() - report.loopSuspectedCount()) / report.totalGames());
    }

    @Test
    void houseHoardingWeaponFourPlayerLarge() {
        StrongBotConfig weaponOn  = StrongBotConfig.defaults();
        StrongBotConfig weaponOff = StrongBotConfig.defaults().toBuilder().houseHoardingWeight(0.0).build();

        var report = MatchScheduler.runFourPlayerAB(
                new MatchScheduler.StrategyEntry("hoarding-on",  new PureDomainStrategy(weaponOn)),
                new MatchScheduler.StrategyEntry("hoarding-off", new PureDomainStrategy(weaponOff)),
                100, 20_000, 77_000L);  // 100 seeds × 6 arrangements = 600 games

        System.out.println(report.summary());
    }
}
