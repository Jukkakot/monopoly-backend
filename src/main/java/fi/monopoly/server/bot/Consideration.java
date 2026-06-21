package fi.monopoly.server.bot;

import java.util.List;

/**
 * One normalized scoring axis in the Infinite Axis Utility System (IAUS).
 *
 * <p>A consideration evaluates a {@link CandidateAction} in context and returns
 * a value in [0,1]. A score of 0 vetoes the action from selection (when using
 * multiplicative combination). Considerations are pure functions — no side effects,
 * no I/O, deterministic given the same {@link DecisionContext}.</p>
 *
 * <h3>Combination (Dave Mark multiplicative with compensation factor)</h3>
 * <pre>
 *   raw = Π(consideration_i.score(ctx))
 *   modFactor = 1 - 1/n        // n = number of considerations
 *   makeUp = (1 - raw) * modFactor
 *   finalScore = raw + makeUp * raw
 * </pre>
 *
 * @see #combine(List, DecisionContext)
 */
public interface Consideration {

    /** Unique identifier used as weight map key in {@link BotParams}. */
    String id();

    /**
     * Returns a utility score in [0,1] for the candidate action in {@code ctx}.
     *
     * <p>0 = the action is impossible or extremely undesirable (acts as a veto when
     * combined multiplicatively). 1 = maximally desirable on this axis.</p>
     */
    double score(DecisionContext ctx);

    // -------------------------------------------------------------------------
    // Combiner (static utility — does not belong to a specific consideration)
    // -------------------------------------------------------------------------

    /**
     * Combines multiple consideration scores for the same {@link DecisionContext}
     * using the Dave Mark multiplicative + compensation factor formula.
     *
     * <p>Any consideration scoring 0 (e.g. a veto) collapses the combined score to 0,
     * disqualifying the action from selection.</p>
     *
     * @param considerations ordered list of considerations to evaluate
     * @param ctx            the context (action + state + memory + params)
     * @return combined utility in [0,1]
     */
    static double combine(List<Consideration> considerations, DecisionContext ctx) {
        int n = considerations.size();
        if (n == 0) return 0.0;

        double raw = 1.0;
        for (Consideration c : considerations) {
            double weight = ctx.params().weights().getOrDefault(c.id(), 1.0);
            raw *= Math.max(0.0, Math.min(1.0, c.score(ctx))) * weight;
            if (raw == 0.0) return 0.0; // early-out on veto
        }

        // Dave Mark compensation factor — prevents extreme decay with many considerations
        double modFactor = 1.0 - 1.0 / n;
        double makeUp    = (1.0 - raw) * modFactor;
        return raw + makeUp * raw;
    }
}
