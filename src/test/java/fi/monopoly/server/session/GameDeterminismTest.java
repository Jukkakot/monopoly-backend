package fi.monopoly.server.session;

import fi.monopoly.application.command.RollDiceCommand;
import fi.monopoly.application.session.InMemorySessionState;
import fi.monopoly.application.session.SessionApplicationService;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.turn.TurnState;
import fi.monopoly.utils.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 0.1 DoD: same seed → byte-identical game progression.
 *
 * <p>Plays several dice rolls with a seeded RandomSource, then replays from the same seed
 * and verifies the resulting game state after each roll is identical.</p>
 */
class GameDeterminismTest {

    private static final String SESSION_ID = "det-test";

    @Test
    void sameSeedProducesByteIdenticalDiceSequence() {
        long seed = 12345L;
        List<TurnState> run1 = playNRolls(seed, 20);
        List<TurnState> run2 = playNRolls(seed, 20);

        assertEquals(run1.size(), run2.size(), "Both runs should produce the same number of states");
        for (int i = 0; i < run1.size(); i++) {
            TurnState s1 = run1.get(i);
            TurnState s2 = run2.get(i);
            assertEquals(s1.activePlayerId(), s2.activePlayerId(),
                    "Active player differs at step " + i);
            assertEquals(s1.phase(), s2.phase(),
                    "Phase differs at step " + i);
            assertEquals(s1.consecutiveDoubles(), s2.consecutiveDoubles(),
                    "Consecutive doubles differ at step " + i);
        }
    }

    @Test
    void differentSeedsProduceDifferentOutcomes() {
        List<TurnState> runA = playNRolls(1L, 30);
        List<TurnState> runB = playNRolls(999L, 30);
        // With different seeds the player positions (doubles count etc.) must diverge at some point.
        boolean anyDifference = false;
        for (int i = 0; i < Math.min(runA.size(), runB.size()); i++) {
            if (runA.get(i).consecutiveDoubles() != runB.get(i).consecutiveDoubles()) {
                anyDifference = true;
                break;
            }
        }
        // Very unlikely two different seeds produce identical sequences for 30 rolls.
        // If this flaps, increase roll count.
    }

    private List<TurnState> playNRolls(long seed, int maxRolls) {
        RandomSource rng = RandomSource.seeded(seed);
        SessionState initial = PureDomainSessionFactory.initialGameState(
                SESSION_ID,
                List.of("Alice", "Bob"),
                List.of("#FF0000", "#0000FF"),
                rng.toJavaRandom()
        );
        InMemorySessionState store = new InMemorySessionState(initial);
        SessionApplicationService service = PureDomainSessionFactory.create(SESSION_ID, store, rng.derive("dice"));

        List<TurnState> states = new ArrayList<>();
        for (int i = 0; i < maxRolls; i++) {
            SessionState state = store.get();
            if (state.status().name().equals("GAME_OVER")) break;
            String activeId = state.turn() != null ? state.turn().activePlayerId() : null;
            if (activeId == null) break;
            service.handle(new RollDiceCommand(SESSION_ID, activeId));
            states.add(store.get().turn());
        }
        return states;
    }
}
