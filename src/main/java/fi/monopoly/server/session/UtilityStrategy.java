package fi.monopoly.server.session;

import fi.monopoly.domain.decision.PendingDecision;
import fi.monopoly.domain.decision.PropertyPurchaseDecisionPayload;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.turn.TurnPhase;
import fi.monopoly.server.bot.*;
import fi.monopoly.utils.RandomSource;

import java.util.Map;

/**
 * Phase 3.3: utility-AI strategy that handles the buy-vs-auction decision via
 * the IAUS model and delegates everything else to {@link PureDomainStrategy}.
 *
 * <p>This is the entry point for the new bot logic. One decision at a time is migrated
 * from the old greedy path to the utility model; until all decisions are migrated,
 * {@code PureDomainStrategy} handles the remainder.</p>
 *
 * <h3>Buy/auction decision (Phase 3)</h3>
 * <p>When the game is in {@code WAITING_FOR_DECISION} with a property purchase payload,
 * {@link BuyConsiderations} scores the BUY action with the multiplicative+compensation
 * combiner, and compares it to the {@code auction_baseline} threshold. Everything else
 * is delegated.</p>
 *
 * <h3>Production default</h3>
 * <p>This strategy is NOT the production default. It runs only when explicitly selected
 * via the harness or a per-session flag (see {@link PureDomainBotDriver#USE_NEW_STRATEGY}).
 * The gauntlet must show parity with {@code pure-domain-v1} before promotion.</p>
 */
public final class UtilityStrategy implements BotStrategy {

    private final PureDomainStrategy delegate;
    private final BotParams defaultParams;

    /**
     * Constructs a {@code UtilityStrategy} backed by the given per-player configs.
     *
     * @param configs per-player {@link StrongBotConfig} — forwarded to the delegate
     *                strategy for all decisions not yet migrated to utility
     */
    public UtilityStrategy(Map<String, StrongBotConfig> configs) {
        this.delegate = new PureDomainStrategy(configs);
        this.defaultParams = BotParams.defaults();
    }

    /** Convenience constructor — no per-player config override. */
    public UtilityStrategy() {
        this(Map.of());
    }

    @Override
    public String name() { return "utility-v1"; }

    @Override
    public Intent decide(SessionState state, String botId, BotMemory memory, RandomSource rng) {
        // ---- Phase 3: handle buy-vs-auction via utility model ----------------
        if (isBuyDecision(state, botId)) {
            Intent utilityIntent = decidePurchase(state, botId, memory, rng);
            if (utilityIntent != null) return utilityIntent;
        }

        // ---- All other decisions: delegate to PureDomainStrategy -------------
        return delegate.decide(state, botId, memory, rng);
    }

    // -------------------------------------------------------------------------
    // Buy / auction decision via IAUS
    // -------------------------------------------------------------------------

    private static boolean isBuyDecision(SessionState state, String botId) {
        if (state.turn() == null) return false;
        if (state.turn().phase() != TurnPhase.WAITING_FOR_DECISION) return false;
        PendingDecision pd = state.pendingDecision();
        if (pd == null) return false;
        if (!(pd.payload() instanceof PropertyPurchaseDecisionPayload)) return false;
        // Only act when it's actually this bot's decision
        return botId.equals(state.turn().activePlayerId());
    }

    private Intent decidePurchase(SessionState state, String botId,
                                  BotMemory memory, RandomSource rng) {
        PendingDecision pd = state.pendingDecision();
        if (!(pd.payload() instanceof PropertyPurchaseDecisionPayload purchase)) return null;

        String propId     = purchase.propertyId();
        int    price      = purchase.price();
        String decisionId = pd.decisionId();

        BotParams params = paramsFor(botId);

        // Score the BUY candidate
        CandidateAction buyAction = new CandidateAction.BuyProperty(propId, price);
        DecisionContext buyCtx    = new DecisionContext(state, botId, memory, params, buyAction);
        double buyScore = Consideration.combine(BuyConsiderations.BUY_CONSIDERATIONS, buyCtx);

        // The "decline and go to auction" baseline
        double auctionBaseline = params.weight("auction_baseline", 0.25);

        if (buyScore > auctionBaseline) {
            return new Intent.BuyProperty(decisionId, propId);
        }
        return new Intent.DeclineProperty(decisionId, propId);
    }

    // -------------------------------------------------------------------------
    // Per-player params
    // -------------------------------------------------------------------------

    private BotParams paramsFor(String botId) {
        // Phase 5 will load personality-specific params per bot.
        // For Phase 3, all bots use the same default BotParams.
        return defaultParams;
    }
}
