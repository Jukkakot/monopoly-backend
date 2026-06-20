package fi.monopoly.server.bot;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * First-class, comparable value representing one trade proposal.
 *
 * <p>Identity is based on the swap structure (partner + property sets), with cash
 * bucketed to prevent tiny adjustments from producing "new" proposals. This lets
 * the {@link ProposalLedger} detect when a re-proposed trade is materially identical
 * to a previously declined one, structurally preventing trade-loop re-opens.</p>
 *
 * @param partnerId   the counterparty
 * @param give        properties and cash the bot is offering
 * @param get         properties and cash the bot wants in return
 */
public record TradeProposal(String partnerId, Side give, Side get) {

    /** One side of a trade. */
    public record Side(Set<String> propertyIds, long cash) {
        public Side {
            propertyIds = Set.copyOf(new TreeSet<>(propertyIds)); // canonical ordering
        }

        public Side(Collection<String> properties, long cash) {
            this(new TreeSet<>(properties), cash);
        }

        public static Side empty() { return new Side(Set.of(), 0); }

        /** True if this side is strictly more valuable: more cash or more/better properties. */
        public boolean strictlyMoreThan(Side other) {
            long cashDiff = this.cash - other.cash;
            int propDiff  = this.propertyIds.size() - other.propertyIds.size();
            return cashDiff > 50 || propDiff > 0;  // $50 tolerance bucket
        }
    }

    /**
     * Canonical ID used by the ledger: (partner, give-props, get-props, cash-buckets).
     * Cash is bucketed to €50 to avoid trivial-jitter proposals registering as new.
     */
    public String id() {
        return partnerId
                + '|' + give.propertyIds()
                + '|' + get.propertyIds()
                + '|' + bucket(give.cash())
                + '|' + bucket(get.cash());
    }

    /**
     * True when this proposal is the same swap structure but offers better terms
     * to the partner than {@code prior} — i.e. this bot gives more.
     */
    public boolean strictlyImproves(TradeProposal prior) {
        if (!partnerId.equals(prior.partnerId)) return false;
        if (!get.propertyIds().equals(prior.get.propertyIds())) return false;
        if (!give.propertyIds().equals(prior.give.propertyIds())) return false;
        // Same structure — does this proposal give the partner more cash?
        return give.cash() > prior.give.cash() + 50;
    }

    private static long bucket(long cash) { return (cash / 50) * 50; }
}
