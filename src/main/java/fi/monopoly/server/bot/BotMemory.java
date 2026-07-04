package fi.monopoly.server.bot;

import fi.monopoly.domain.session.TradeOfferState;
import fi.monopoly.domain.session.TradeSelectionState;
import fi.monopoly.domain.session.TradeState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1.1: explicit, per-game cross-turn memory for one bot.
 *
 * <p>Replaces the hidden mutable fields on {@code PureDomainBotDriver}
 * ({@code tradeDeclinesByPartnerId}, {@code declinedSwapTargets},
 * {@code lastDeclinedOfferAmount}, {@code counterEditAttempts},
 * {@code turnsSinceMonopolyChange}, etc.) with a single, passed-explicitly value.
 * Making state visible prevents the trade-loop class of bug.</p>
 *
 * <p>This implementation is <em>mutable</em> (for Phase 1 simplicity) but
 * <em>owned by one thread</em> — the bot executor. It will be made immutable
 * (pure functional update) in Phase 3 when the harness needs thread safety.</p>
 */
public final class BotMemory {

    // -------------------------------------------------------------------------
    // Trade decline tracking  (mirrors current PureDomainBotDriver fields)
    // -------------------------------------------------------------------------

    /** How many times each partner has declined a bot-initiated trade. */
    private final Map<String, Integer> declinesByPartnerId = new ConcurrentHashMap<>();

    /**
     * Per-partner set of property IDs the partner refused to give up.
     * Recorded at the bot's OWN decline decision to avoid fast-reopen dropping the signal.
     */
    private final Map<String, Set<String>> declinedSwapTargets = new ConcurrentHashMap<>();

    /**
     * Last cash amount the bot offered per (partnerId → propertyId) that was declined.
     * Used to enforce "must strictly beat the last declined offer" before re-proposing.
     */
    private final Map<String, Map<String, Integer>> lastDeclinedOfferAmount = new ConcurrentHashMap<>();

    /** Consecutive EditTradeOffer attempts per tradeId — safety net against edit loops. */
    private final Map<String, Integer> counterEditAttempts = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Stalemate tracking
    // -------------------------------------------------------------------------

    private volatile int turnsSinceMonopolyChange = 0;
    private volatile int lastMonopolyCount = 0;

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int declineCount(String partnerId) {
        return declinesByPartnerId.getOrDefault(partnerId, 0);
    }

    public void recordDecline(String partnerId) {
        declinesByPartnerId.merge(partnerId, 1, Integer::sum);
    }

    public Set<String> declinedSwapTargets(String partnerId) {
        return declinedSwapTargets.getOrDefault(partnerId, Set.of());
    }

    public void recordDeclinedSwapTarget(String partnerId, String propertyId) {
        declinedSwapTargets.computeIfAbsent(partnerId, k -> ConcurrentHashMap.newKeySet()).add(propertyId);
    }

    public int lastDeclinedAmount(String partnerId, String propertyId) {
        Map<String, Integer> inner = lastDeclinedOfferAmount.get(partnerId);
        return inner != null ? inner.getOrDefault(propertyId, 0) : 0;
    }

    public void recordDeclinedAmount(String partnerId, String propertyId, int amount) {
        lastDeclinedOfferAmount
                .computeIfAbsent(partnerId, k -> new ConcurrentHashMap<>())
                .put(propertyId, amount);
    }

    public int counterEditAttempts(String tradeId) {
        return counterEditAttempts.getOrDefault(tradeId, 0);
    }

    public int incrementCounterEdits(String tradeId) {
        return counterEditAttempts.merge(tradeId, 1, Integer::sum);
    }

    public void clearCounterEdits(String tradeId) {
        counterEditAttempts.remove(tradeId);
    }

    public int turnsSinceMonopolyChange() { return turnsSinceMonopolyChange; }
    public int lastMonopolyCount()        { return lastMonopolyCount; }

    public void updateMonopolyTracking(int currentMonopolyCount) {
        if (currentMonopolyCount == lastMonopolyCount) {
            turnsSinceMonopolyChange++;
        } else {
            turnsSinceMonopolyChange = 0;
            lastMonopolyCount = currentMonopolyCount;
        }
    }

    /** Returns a fresh, empty memory instance for use at game start. */
    public static BotMemory empty() {
        return new BotMemory();
    }

    // -------------------------------------------------------------------------
    // Cross-memory bridge
    // -------------------------------------------------------------------------

    /**
     * Records a trade failure in the <em>opener's</em> memory when the trade was killed by the
     * other party — a decline of a submitted offer, or a cancel during counter-editing.
     *
     * <p>This is the only place the opener learns its offer failed: the strategy layer only ever
     * sees the acting bot's own memory, so the decliner cannot write into the opener's memory
     * itself. Without this bridge the opener's {@code declineCount}, {@code declinedSwapTargets}
     * and {@code lastDeclinedAmount} guards stay empty and the opener re-proposes the identical
     * trade indefinitely (the bot-vs-bot trade-loop bug).</p>
     *
     * <p>No-op when the closer <em>is</em> the opener (self-cancels are recorded by the strategy
     * via {@code cancelAsDecline}) or when the opener has no memory (human player).</p>
     */
    public static void recordTradeKilledByPartner(Map<String, BotMemory> memories,
                                                  TradeState trade, String closerId) {
        if (trade == null || closerId == null) return;
        String opener = trade.openedByPlayerId();
        if (opener == null || opener.equals(closerId)) return;
        BotMemory openerMemory = memories.get(opener);
        if (openerMemory == null) return;

        String partner = opener.equals(trade.initiatorPlayerId())
                ? trade.recipientPlayerId() : trade.initiatorPlayerId();
        openerMemory.recordDecline(partner);

        TradeOfferState offer = trade.currentOffer();
        if (offer == null) return;
        boolean openerIsInitiator = opener.equals(trade.initiatorPlayerId());
        TradeSelectionState openerWanted = openerIsInitiator
                ? offer.requestedFromRecipient() : offer.offeredToRecipient();
        TradeSelectionState openerGave = openerIsInitiator
                ? offer.offeredToRecipient() : offer.requestedFromRecipient();
        if (openerWanted.propertyIds().isEmpty()) return;

        // A declined cash-only offer may still be escalated to a property swap, so only the
        // amount is recorded (re-offers must strictly beat it). Once a SWAP for the target is
        // declined there is no better offer left to make — block the target entirely, otherwise
        // the opener re-proposes the identical swap forever.
        if (!openerGave.propertyIds().isEmpty()) {
            for (String propId : openerWanted.propertyIds()) {
                openerMemory.recordDeclinedSwapTarget(partner, propId);
            }
        }
        openerMemory.recordDeclinedAmount(
                partner, openerWanted.propertyIds().get(0), openerGave.moneyAmount());
    }
}
