package fi.monopoly.server.bot;

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
}
