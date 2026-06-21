package fi.monopoly.server.bot;

import fi.monopoly.domain.session.SessionState;

/**
 * Bundles everything a {@link Consideration} needs to evaluate one candidate action.
 *
 * <p>A fresh {@code DecisionContext} is created for each (action, state) pair being
 * scored. It is immutable and side-effect-free — safe to pass across multiple
 * considerations in parallel.</p>
 */
public record DecisionContext(
        SessionState state,
        String botId,
        BotMemory memory,
        BotParams params,
        CandidateAction action
) {}
