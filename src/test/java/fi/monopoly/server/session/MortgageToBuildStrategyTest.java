package fi.monopoly.server.session;

import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.domain.session.PropertyStateSnapshot;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.session.SessionStatus;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.server.bot.BotMemory;
import fi.monopoly.server.bot.Intent;
import fi.monopoly.utils.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the mortgage-to-build lever in {@link PureDomainStrategy}.
 *
 * <p>When the bot owns a monopoly worth developing but cannot afford the build round from cash,
 * it should mortgage an expendable non-synergy deed to fund it — never a deed in the monopoly,
 * and never when the lever is disabled.</p>
 */
final class MortgageToBuildStrategyTest {

    private static final String BOT = "bot";
    private static final String OPP = "opp";

    private static final List<String> ALL = List.of(
            "B1", "B2", "LB1", "LB2", "LB3", "P1", "P2", "P3", "O1", "O2", "O3",
            "R1", "R2", "R3", "Y1", "Y2", "Y3", "G1", "G2", "G3", "DB1", "DB2",
            "RR1", "RR2", "RR3", "RR4", "U1", "U2");

    private static PlayerSnapshot player(String id, int cash, List<String> props) {
        return new PlayerSnapshot(id, "seat-" + id, id, cash, 0, false, false, false, 0, 0, props);
    }

    /** Board with the given owned deeds; every other deed is unowned (so reserve logic sees a mid-game board). */
    private static SessionState endTurnState(int botCash, List<String> botProps, List<String> oppProps) {
        List<PropertyStateSnapshot> props = new ArrayList<>();
        for (String id : ALL) {
            String owner = botProps.contains(id) ? BOT : oppProps.contains(id) ? OPP : null;
            props.add(new PropertyStateSnapshot(id, owner, false, 0, 0));
        }
        return SessionState.builder()
                .sessionId("t").version(1).status(SessionStatus.IN_PROGRESS)
                .seats(List.of())
                .players(List.of(player(BOT, botCash, botProps), player(OPP, 500, oppProps)))
                .properties(props)
                .turn(new TurnState(BOT, TurnPhase.WAITING_FOR_END_TURN, false, true))
                .build();
    }

    private static Intent decide(SessionState s, boolean mortgageToBuild) {
        StrongBotConfig cfg = StrongBotConfig.defaults().toBuilder().mortgageToBuild(mortgageToBuild).build();
        PureDomainStrategy strategy = new PureDomainStrategy(cfg);
        return strategy.decide(s, BOT, new BotMemory(), RandomSource.seeded(1L));
    }

    @Test
    void mortgagesExpendableDeedToFundBuildWhenCashShort() {
        // Bot owns the BROWN monopoly (round cost 100) but only €200 cash — short of reserve+round.
        // It also owns three railroads (expendable, ~€300 raisable). The lever should mortgage one.
        SessionState s = endTurnState(200, List.of("B1", "B2", "RR1", "RR2", "RR3"), List.of());

        Intent intent = decide(s, true);
        assertInstanceOf(Intent.MortgageProperty.class, intent,
                "Expected the bot to mortgage to fund the build, got " + intent);
        String mortgaged = ((Intent.MortgageProperty) intent).propertyId();
        assertTrue(mortgaged.startsWith("RR"),
                "Should mortgage an expendable railroad, not a monopoly deed (got " + mortgaged + ")");
    }

    @Test
    void doesNotMortgageWhenLeverDisabled() {
        SessionState s = endTurnState(200, List.of("B1", "B2", "RR1", "RR2", "RR3"), List.of());

        Intent intent = decide(s, false);
        assertFalse(intent instanceof Intent.MortgageProperty,
                "With the lever off the bot must not mortgage to build (got " + intent + ")");
    }

    @Test
    void doesNotMortgageWhenNoMonopolyToBuild() {
        // Bot owns only a partial group + railroads — nothing to build, so no mortgage-to-build.
        SessionState s = endTurnState(200, List.of("B1", "RR1", "RR2", "RR3"), List.of());

        Intent intent = decide(s, true);
        assertFalse(intent instanceof Intent.MortgageProperty,
                "No completed monopoly ⇒ must not mortgage to build (got " + intent + ")");
    }
}
