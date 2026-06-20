package fi.monopoly.server.bot;

import fi.monopoly.domain.session.SessionState;
import fi.monopoly.utils.RandomSource;

/**
 * Phase 1.1: comparison seam between old and new bot strategies.
 *
 * <p>Both {@code PureDomainStrategy} (wrapping existing logic) and the future
 * {@code UtilityStrategy} implement this interface. The evaluation harness drives
 * any implementation through {@code HeadlessGameRunner} without touching HTTP/SSE.</p>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Pure-ish:</b> no game mutation, no {@code publisher.handle}, no I/O, no clock.</li>
 *   <li><b>All randomness via {@code rng}:</b> no direct {@code Math.random()} or
 *       {@link java.util.concurrent.ThreadLocalRandom} calls inside {@code decide}.</li>
 *   <li><b>Stateless or per-game instanced:</b> no shared mutable statics — the harness
 *       runs thousands of games in parallel.</li>
 * </ul>
 */
public interface BotStrategy {

    /**
     * Decide the next action for the current actor given the game state.
     *
     * @param state  the current (immutable) game state
     * @param botId  the player ID this strategy is acting for
     * @param memory cross-turn memory for this bot this game (mutable, owned by caller)
     * @param rng    seeded random source; use this for any non-deterministic choice
     * @return an {@link Intent} describing the desired action
     */
    Intent decide(SessionState state, String botId, BotMemory memory, RandomSource rng);

    /**
     * A stable, versioned identifier, e.g. {@code "pure-domain-v1"}, {@code "utility-v3"}.
     * Used to label harness reports and prevent comparing apples to oranges.
     */
    String name();
}
