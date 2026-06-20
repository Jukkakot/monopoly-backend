package fi.monopoly.server.bot;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-bot, per-game authority for trade proposal outcomes.
 *
 * <p>Every proposal the bot considers (propose, counter, accept-eval) is recorded here.
 * Before re-opening a trade the planner checks {@link #isFailed}: a failed proposal is
 * only reconsidered if the new version {@link TradeProposal#strictlyImproves(TradeProposal)}
 * the prior one. This monotonic, point-of-decision record makes the trade-loop class of bug
 * <em>structurally impossible</em> — the loop cannot restart because the ledger blocks it.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Bot decides to propose → {@link #record(String, Outcome)} with PROPOSED</li>
 *   <li>Partner responds → {@link #record(String, Outcome)} with ACCEPTED, DECLINED, or COUNTERED</li>
 *   <li>Bot checks {@link #isFailed(String)} before re-opening; must also pass
 *       {@link TradeProposal#strictlyImproves} to be allowed through.</li>
 * </ol>
 */
public final class ProposalLedger {

    public enum Outcome { PROPOSED, ACCEPTED, DECLINED, COUNTERED }

    private final Map<String, Outcome>       outcomes  = new HashMap<>();
    private final Map<String, TradeProposal> proposals = new HashMap<>();

    public static ProposalLedger empty() { return new ProposalLedger(); }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    public void record(TradeProposal proposal, Outcome outcome) {
        outcomes.put(proposal.id(), outcome);
        proposals.put(proposal.id(), proposal);
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    public Outcome outcomeOf(String proposalId) { return outcomes.get(proposalId); }

    /**
     * True when the proposal was previously attempted and the result was negative
     * (DECLINED or COUNTERED), with no subsequent success.
     *
     * <p>A failed proposal should only be reconsidered if the new version
     * {@link TradeProposal#strictlyImproves} the prior one.</p>
     */
    public boolean isFailed(String proposalId) {
        Outcome o = outcomes.get(proposalId);
        return o == Outcome.DECLINED || o == Outcome.COUNTERED;
    }

    /**
     * True when a structurally-equivalent proposal exists that the given {@code candidate}
     * does NOT strictly improve — i.e. this would be a pure re-open of a failed deal.
     */
    public boolean wouldLoop(TradeProposal candidate) {
        String id = candidate.id();
        if (!isFailed(id)) return false;
        TradeProposal prior = proposals.get(id);
        return prior == null || !candidate.strictlyImproves(prior);
    }

    // -------------------------------------------------------------------------
    // Partner-level queries (for the trade-willing check)
    // -------------------------------------------------------------------------

    /** Count of DECLINED outcomes involving {@code partnerId}. */
    public long declineCount(String partnerId) {
        return proposals.values().stream()
                .filter(p -> p.partnerId().equals(partnerId))
                .map(p -> outcomes.get(p.id()))
                .filter(o -> o == Outcome.DECLINED)
                .count();
    }
}
